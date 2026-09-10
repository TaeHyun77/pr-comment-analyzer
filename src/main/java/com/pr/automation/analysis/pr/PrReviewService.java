package com.pr.automation.analysis.pr;

import com.pr.automation.analysis.pr.agent.PrReviewAgent;
import com.pr.automation.analysis.pr.dto.PrReviewEvent;
import com.pr.automation.analysis.pr.dto.PrReviewResult;
import com.pr.automation.config.AsyncConfig;
import com.pr.automation.config.properties.PrReviewProperties;
import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import com.pr.automation.github.GithubClient;
import com.pr.automation.github.GithubClient.ChangedFile;
import com.pr.automation.github.RepoCheckout;
import com.pr.automation.github.RepoCheckoutFactory;
import com.pr.automation.slack.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PR 생성 시 웹훅을 받아 에이전트 리뷰 파이프라인 전체를 오케스트레이션하는 서비스 계층
 * 게시 여부 확인 → 변경 파일 조회 → 에이전트 리뷰 → PR 코멘트 게시 → (옵션) Slack 순으로 진행합니다.
 *
 * 진행 상태를 저장하지 않는 단발 작업입니다 — 실패하면 알림만 남기고 끝나며, 자동 재시도는 하지 않습니다.
 * 중복 게시 방지는 GitHub에 남은 마커 코멘트만으로 판정합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrReviewService {
    private final GithubClient githubClient;
    private final PrReviewAgent reviewAgent;
    private final PrReviewCommentFormatter formatter;
    private final SlackNotifier slackNotifier;
    private final PrReviewProperties properties;
    private final RepoCheckoutFactory repoCheckoutFactory;

    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    public void reviewAsync(PrReviewEvent event) {
        try {
            review(event);
        } catch (Exception e) {
            log.error("PR 자동 리뷰 실패: {} #{}", event.getRepoFullName(), event.getPrNumber(), e);
            slackNotifier.sendPrReviewFailure(event, e);
        }
    }

    private void review(PrReviewEvent event) {
        if (!githubClient.isEnabled()) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REPO_NOT_READABLE, "GitHub 토큰 미설정으로 PR 리뷰 불가");
        }

        /* 리뷰 전에 확인한다 — 같은 PR로 웹훅이 다시 들어와도 LLM 호출까지 가지 않고 멈춘다.
         * 조회 실패는 예외로 전파해 게시하지 않는다(fail-closed).
         * 거의 동시에 도착한 두 웹훅은 이 검사로 걸러지지 않지만, 그 대가로 비싼 LLM 호출을 아낀다 */
        if (githubClient.hasIssueCommentWithMarker(event.getRepoFullName(), event.getPrNumber(), PrReviewCommentFormatter.MARKER)) {
            log.info("리뷰 코멘트가 이미 게시됨(마커 발견) — 리뷰 생략: {} #{}", event.getRepoFullName(), event.getPrNumber());
            return;
        }

        List<ChangedFile> files = githubClient.fetchPullFiles(event.getRepoFullName(), event.getPrNumber())
                .orElseThrow(() -> new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                        "PR 변경 파일 조회 실패: " + event.getRepoFullName() + " #" + event.getPrNumber()));
        if (files.isEmpty()) {
            log.info("변경 파일 없음 — PR 리뷰 생략: {} #{}", event.getRepoFullName(), event.getPrNumber());
            return;
        }

        PrReviewResult result = runReview(event, files);
        githubClient.createIssueComment(event.getRepoFullName(), event.getPrNumber(), formatter.format(result));
        log.info("PR 자동 리뷰 완료: {} #{}", event.getRepoFullName(), event.getPrNumber());

        // 보조 채널 실패는 삼킨다 — 본질 산출물(PR 코멘트)은 이미 게시됐으므로 리뷰 전체를 실패로 만들지 않는다
        if (properties.isPostToSlack()) {
            try {
                slackNotifier.sendPrReview(event, result);
            } catch (Exception e) {
                log.warn("PR 리뷰 보조 Slack 전송 실패(리뷰는 게시 완료): {} #{}", event.getRepoFullName(), event.getPrNumber(), e);
            }
        }
    }

    // PR head 커밋을 체크아웃해 에이전트가 로컬에서 직접 탐색하게 한다
    private PrReviewResult runReview(PrReviewEvent event, List<ChangedFile> files) {
        // 체크아웃은 리뷰 동안만 살아 있으면 된다 — 예외가 나도 close에서 지워진다
        try (RepoCheckout checkout = repoCheckoutFactory.checkout(event.getRepoFullName(), event.getHeadSha())) {
            return reviewAgent.review(event, files, checkout.dir());
        }
    }
}
