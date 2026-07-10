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
 * PR 생성 시 4단계 자동 리뷰 파이프라인
 * 중복 확인 → 변경 파일 조회 → 4단계 리뷰 → PR 코멘트 게시 → (옵션) Slack → 처리 기록
 *
 * deliveryStore.markReceived는 성공 시점에만 호출 — 실패 시 호출하지 않아 recoverer가 재전송할 수 있게 함
 * CommentAnalysisService와 동일한 점유/ack 규약을 따릅니다.
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

        List<ChangedFile> files = githubClient.fetchPullFiles(event.getRepoFullName(), event.getPrNumber());
        if (files.isEmpty()) {
            log.info("변경 파일 없음(또는 조회 실패) — PR 리뷰 생략: {} #{}", event.getRepoFullName(), event.getPrNumber());
            return;
        }

        PrReviewResult result = pipeline.run(event, files);

        String body = formatter.format(result);
        githubClient.createIssueComment(event.getRepoFullName(), event.getPrNumber(), body);

        if (properties.isPostToSlack()) {
            slackNotifier.sendPrReview(event, result);
        }
    }
}
