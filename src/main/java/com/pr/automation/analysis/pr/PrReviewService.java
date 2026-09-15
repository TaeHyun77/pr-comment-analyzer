package com.pr.automation.analysis.pr;

import com.pr.automation.analysis.pr.agent.PrReviewAgent;
import com.pr.automation.analysis.pr.dto.PrReviewEvent;
import com.pr.automation.analysis.pr.dto.PrReviewResult;
import com.pr.automation.config.AsyncConfig;
import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import com.pr.automation.github.GithubClient;
import com.pr.automation.github.GithubClient.ChangedFile;
import com.pr.automation.github.RepoCheckout;
import com.pr.automation.github.RepoCheckoutFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PR 생성 웹훅을 받아 에이전트 리뷰 전체 과정을 처리합니다.
 * 게시 여부 확인 → 변경 파일 조회 → 에이전트 리뷰 → PR 코멘트 게시
 * 실패 시 로그만 남기고 종료합니다.
 * 결과는 PR 코멘트로 확인하며, 중복 게시 여부는 GitHub 마커 코멘트로 판단합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrReviewService {
    private final GithubClient githubClient;
    private final PrReviewAgent reviewAgent;
    private final PrReviewCommentFormatter formatter;
    private final RepoCheckoutFactory repoCheckoutFactory;

    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    public void reviewAsync(PrReviewEvent event) {
        try {
            prReview(event);
        } catch (Exception e) {
            log.error("PR 자동 리뷰 실패: {} #{}", event.getRepoFullName(), event.getPrNumber(), e);
        }
    }

    private void prReview(PrReviewEvent event) {
        if (!githubClient.isEnabled()) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REPO_NOT_READABLE, "GitHub 토큰 미설정으로 PR 리뷰 불가");
        }

        // 리뷰 전에 중복 분석 확인 - 같은 PR로 웹훅이 다시 들어와도 LLM 호출까지 가지 않고 멈춤
        if (githubClient.hasIssueCommentWithMarker(event.getRepoFullName(), event.getPrNumber(), PrReviewCommentFormatter.MARKER)) {
            log.info("리뷰 코멘트가 이미 게시됨 - 리뷰 생략: {} #{}", event.getRepoFullName(), event.getPrNumber());
            return;
        }

        // GitHub PR 화면의 "Files changed" 탭에 보이는 내용을 API로 받는 것
        // 어떤 파일이 바뀌었는지와 각 파일이 어떻게 바뀌었는지 (diff)
        // 파일 전체 내용은 받지 않음 - 파일 전체는 체크아웃 폴더에서 에이전트가 읽음
        List<ChangedFile> files = githubClient.fetchPullFiles(event.getRepoFullName(), event.getPrNumber())
                .orElseThrow(() -> new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                        "PR 변경 파일 조회 실패: " + event.getRepoFullName() + " #" + event.getPrNumber())
                );
        if (files.isEmpty()) {
            log.info("변경 파일 없음 - PR 리뷰 생략: {} #{}", event.getRepoFullName(), event.getPrNumber());
            return;
        }

        // PR head 커밋 시점의 저장소 파일 전체를 임시 폴더에 받음 - PR 브랜치의 최신 커밋 시점 프로젝트 전체
        // 에이전트는 이중 판단에 필요한 파일만 골라 읽습니다.
        PrReviewResult result = runReview(event, files);
        githubClient.createIssueComment(event.getRepoFullName(), event.getPrNumber(), formatter.format(result));
        log.info("PR 자동 리뷰 완료: {} #{}", event.getRepoFullName(), event.getPrNumber());
    }

    private PrReviewResult runReview(PrReviewEvent event, List<ChangedFile> files) {
        // 체크아웃은 리뷰 동안만 살아 있으면 됨 - 예외가 나도 close에서 지워짐
        try (RepoCheckout checkout = repoCheckoutFactory.checkout(event.getRepoFullName(), event.getHeadSha())) {
            return reviewAgent.review(event, files, checkout.dir());
        }
    }
}
