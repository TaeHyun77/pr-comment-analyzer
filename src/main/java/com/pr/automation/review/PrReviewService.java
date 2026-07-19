package com.pr.automation.review;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 중복 확인 → (저장 결과 복원 또는) 변경 파일 조회 → 4단계 리뷰 → 결과 영속화 → 멱등 게시 → (옵션) Slack → 처리 기록
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
    private final ObjectMapper objectMapper;

    // deliveryStore.markReceived는 성공 시점에만 호출 — 실패 시 호출하지 않아 recoverer가 재전송할 수 있게 함
    // 리뷰 성공 후 게시만 실패한 경우 결과가 ANALYZED 행에 남아, 재전송 시 LLM 없이 게시만 재시도됨
    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    public void reviewAsync(PrReviewEvent event) {
        String key = event.getRepoFullName() + "#" + event.getPrNumber();

        if (!reviewStore.tryClaim(key)) {
            log.info("이미 리뷰 중이거나 완료된 PR, 건너뜀: {}", key);
            deliveryStore.markReceived(event.getDeliveryId());
            return;
        }

        try {
            review(event, key);
            reviewStore.markCompleted(key);
            deliveryStore.markReceived(event.getDeliveryId());
            log.info("PR 자동 리뷰 완료: {}", key);
        } catch (Exception e) {
            // 점유 해제 - releaseClaim이 IN_PROGRESS만 삭제하므로 ANALYZED(게시만 실패)는 자동 보존됨
            reviewStore.release(key);
            log.error("PR 자동 리뷰 실패: {}", key, e);
            slackNotifier.sendPrReviewFailure(event, e);
        }
    }

    public void review(PrReviewEvent event, String key) {
        if (!githubClient.isEnabled()) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REPO_NOT_READABLE, "GitHub 토큰 미설정으로 PR 리뷰 불가");
        }

        PrReviewResult result = loadSavedResult(key);
        if (result != null) {
            log.info("저장된 리뷰 결과 재사용 - 게시만 재시도: {}", key);
        } else {
            // 조회 실패는 "변경 없음"과 달리 예외로 전환 — 완료로 봉인되지 않고 복구 사이클(redelivery)이 재시도하게 함
            List<ChangedFile> files = githubClient.fetchPullFiles(event.getRepoFullName(), event.getPrNumber())
                    .orElseThrow(() -> new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                            "PR 변경 파일 조회 실패: " + event.getRepoFullName() + " #" + event.getPrNumber()));
            if (files.isEmpty()) {
                log.info("변경 파일 없음 — PR 리뷰 생략: {} #{}", event.getRepoFullName(), event.getPrNumber());
                return;
            }

            result = pipeline.run(event, files);
            // 게시 전에 자체 트랜잭션으로 즉시 커밋됨(서비스에 @Transactional 없음이 전제)
            // — 이후 게시가 실패해도 결과가 보존되어 재리뷰를 막는다
            reviewStore.markAnalyzed(key, serializeResult(result));
        }

        // 멱등 게시: 이전 시도에서 게시 성공 후 완료 기록 전에 크래시한 재진입이면 마커 코멘트가 이미 존재 - 중복 게시 방지
        if (githubClient.hasIssueCommentWithMarker(event.getRepoFullName(), event.getPrNumber(), PrReviewCommentFormatter.MARKER)) {
            log.info("리뷰 코멘트가 이미 게시됨(마커 발견) — 게시 생략: {}", key);
        } else {
            githubClient.createIssueComment(event.getRepoFullName(), event.getPrNumber(), formatter.format(result));
        }

        // 보조 채널 실패는 삼킴 — 본질 산출물(PR 코멘트)은 이미 게시됐으므로,
        // 여기서 실패를 전파하면 재실행 시 게시 재시도라는 더 큰 부작용이 생김
        // 재진입으로 게시가 스킵된 경우에도 다시 발송될 수 있음 — 무해한 중복으로 의도된 동작
        if (properties.isPostToSlack()) {
            try {
                slackNotifier.sendPrReview(event, result);
            } catch (Exception e) {
                log.warn("PR 리뷰 보조 Slack 전송 실패(리뷰는 완료 처리): {} #{}", event.getRepoFullName(), event.getPrNumber(), e);
            }
        }
    }

    // ANALYZED 행(이전 시도에서 리뷰는 성공, 게시만 실패)의 저장 결과를 복원. 없거나 깨졌으면 null → 신규 리뷰
    private PrReviewResult loadSavedResult(String key) {
        String json = reviewStore.findAnalyzedResult(key).orElse(null);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PrReviewResult.class);
        } catch (Exception e) {
            log.warn("저장된 리뷰 결과 역직렬화 실패, 신규 리뷰로 진행: {}", key, e);
            return null;
        }
    }

    // 직렬화 실패 시 null 저장(게시 실패 시 재리뷰로 후퇴) — 영속화는 비용 절감용 최적화이므로 리뷰 성공을 실패로 만들지 않음
    private String serializeResult(PrReviewResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("리뷰 결과 직렬화 실패 — 게시 실패 시 재리뷰됨", e);
            return null;
        }
    }
}
