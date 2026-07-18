package com.pr.automation.review;

import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.AsyncConfig;
import com.pr.automation.config.PrReviewProperties;
import com.pr.automation.github.GithubClient;
import com.pr.automation.github.GithubClient.ChangedFile;
import com.pr.automation.review.agent.PrReviewPipeline;
import com.pr.automation.review.dto.PrReviewEvent;
import com.pr.automation.review.dto.PrReviewResult;
import com.pr.automation.slack.SlackNotifier;
import com.pr.automation.webhook.recovery.DeliveryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PR 생성 시, 웹훅을 받아 4단계 리뷰 파이프라인 전체를 오케스트레이션하는 서비스 계층
 * 중복 확인 → 변경 파일 조회 → 4단계 리뷰 → PR 코멘트 게시 → (옵션) Slack → 처리 기록
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrReviewService {
    private final GithubClient githubClient;
    private final PrReviewPipeline pipeline;
    private final PrReviewCommentFormatter formatter;
    private final PrReviewStore reviewStore;
    private final SlackNotifier slackNotifier;
    private final DeliveryStore deliveryStore;
    private final PrReviewProperties properties;

    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    public void reviewAsync(PrReviewEvent event) {
        String key = event.getRepoFullName() + "#" + event.getPrNumber();

        if (!reviewStore.tryClaim(key)) {
            log.info("이미 리뷰 중이거나 완료된 PR, 건너뜀: {}", key);
            deliveryStore.markReceived(event.getDeliveryId());
            return;
        }

        try {
            review(event);
            reviewStore.markCompleted(key);
            deliveryStore.markReceived(event.getDeliveryId());
            log.info("PR 자동 리뷰 완료: {}", key);
        } catch (Exception e) {
            // 실패 시 점유 해제 — 같은 PR의 재시도(부팅/스케줄 recoverer)를 가능하게 함
            reviewStore.release(key);
            log.error("PR 자동 리뷰 실패: {}", key, e);
            slackNotifier.sendPrReviewFailure(event, e);
        }
    }

    public void review(PrReviewEvent event) {
        if (!githubClient.isEnabled()) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REPO_NOT_READABLE, "GitHub 토큰 미설정으로 PR 리뷰 불가");
        }

        // 조회 실패는 "변경 없음"과 달리 예외로 전환 — 완료로 봉인되지 않고 복구 사이클(redelivery)이 재시도하게 함
        List<ChangedFile> files = githubClient.fetchPullFiles(event.getRepoFullName(), event.getPrNumber())
                .orElseThrow(() -> new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                        "PR 변경 파일 조회 실패: " + event.getRepoFullName() + " #" + event.getPrNumber()));
        if (files.isEmpty()) {
            log.info("변경 파일 없음 — PR 리뷰 생략: {} #{}", event.getRepoFullName(), event.getPrNumber());
            return;
        }

        PrReviewResult result = pipeline.run(event, files);

        String body = formatter.format(result);
        githubClient.createIssueComment(event.getRepoFullName(), event.getPrNumber(), body);

        // 보조 채널 실패는 삼킴 — 본질 산출물(PR 코멘트)은 이미 게시됐으므로,
        // 여기서 실패를 전파하면 재실행 시 LLM 재호출 + 중복 코멘트 게시라는 더 큰 부작용이 생김
        if (properties.isPostToSlack()) {
            try {
                slackNotifier.sendPrReview(event, result);
            } catch (Exception e) {
                log.warn("PR 리뷰 보조 Slack 전송 실패(리뷰는 완료 처리): {} #{}", event.getRepoFullName(), event.getPrNumber(), e);
            }
        }
    }
}
