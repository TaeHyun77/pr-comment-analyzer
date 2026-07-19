package com.pr.automation.review;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.PrReviewProperties;
import com.pr.automation.github.GithubClient;
import com.pr.automation.review.agent.PrReviewPipeline;
import com.pr.automation.review.dto.PrReviewEvent;
import com.pr.automation.review.dto.PrReviewResult;
import com.pr.automation.slack.SlackNotifier;
import com.pr.automation.webhook.recovery.DeliveryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrReviewServiceTest {

    private static final PrReviewEvent EVENT = PrReviewEvent.builder()
            .deliveryId("delivery-77")
            .repoFullName("me/repo")
            .prNumber(7)
            .prTitle("제목")
            .build();
    private static final String KEY = "me/repo#7";

    private GithubClient githubClient;
    private PrReviewPipeline pipeline;
    private PrReviewCommentFormatter formatter;
    private PrReviewStore reviewStore;
    private SlackNotifier slackNotifier;
    private DeliveryStore deliveryStore;
    private ObjectMapper objectMapper;
    private PrReviewService service;

    @BeforeEach
    void setUp() {
        githubClient = mock(GithubClient.class);
        pipeline = mock(PrReviewPipeline.class);
        formatter = mock(PrReviewCommentFormatter.class);
        reviewStore = mock(PrReviewStore.class);
        slackNotifier = mock(SlackNotifier.class);
        deliveryStore = mock(DeliveryStore.class);
        objectMapper = new ObjectMapper();
        PrReviewProperties properties = new PrReviewProperties(true, 30, 8000, false, 15);
        when(githubClient.isEnabled()).thenReturn(true);
        service = new PrReviewService(githubClient, pipeline, formatter, reviewStore, slackNotifier, deliveryStore, properties, objectMapper);
    }

    @Test
    void 변경파일_조회_실패시_release하고_ack하지_않는다() {
        when(reviewStore.tryClaim(KEY)).thenReturn(true);
        // 조회 실패(empty)는 "변경 없음"과 달리 완료로 봉인되면 안 됨 — 복구 사이클이 재시도할 수 있어야 함
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.empty());

        service.reviewAsync(EVENT);

        verify(pipeline, never()).run(any(), any());
        verify(reviewStore).release(KEY);
        verify(reviewStore, never()).markCompleted(anyString());
        verify(deliveryStore, never()).markReceived(anyString());
        verify(slackNotifier).sendPrReviewFailure(eq(EVENT), any());
    }

    @Test
    void 변경파일이_정말_없으면_리뷰를_생략하고_완료처리한다() {
        when(reviewStore.tryClaim(KEY)).thenReturn(true);
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.of(Collections.emptyList()));

        service.reviewAsync(EVENT);

        verify(pipeline, never()).run(any(), any());
        verify(githubClient, never()).createIssueComment(anyString(), anyInt(), anyString());
        verify(reviewStore).markCompleted(KEY);
        verify(deliveryStore).markReceived("delivery-77");
        verify(reviewStore, never()).release(anyString());
    }

    @Test
    void 정상경로는_리뷰결과를_PR코멘트로_게시하고_완료기록한다() {
        PrReviewResult result = stubNewReview();

        service.reviewAsync(EVENT);

        verify(githubClient).createIssueComment("me/repo", 7, "리뷰 본문");
        verify(reviewStore).markCompleted(KEY);
        verify(deliveryStore).markReceived("delivery-77");
        verify(reviewStore, never()).release(anyString());
        verify(slackNotifier, never()).sendPrReviewFailure(any(), any());
    }

    @Test
    void 신규리뷰는_게시_전에_결과를_저장한다() {
        stubNewReview();

        service.reviewAsync(EVENT);

        // 게시가 실패해도 결과가 보존되려면 markAnalyzed가 게시보다 먼저 커밋돼야 함
        InOrder order = inOrder(reviewStore, githubClient);
        order.verify(reviewStore).markAnalyzed(eq(KEY), any());
        order.verify(githubClient).createIssueComment("me/repo", 7, "리뷰 본문");
    }

    @Test
    void 저장된_결과가_있으면_파이프라인_없이_게시만_재시도한다() {
        when(reviewStore.tryClaim(KEY)).thenReturn(true);
        when(reviewStore.findAnalyzedResult(KEY)).thenReturn(Optional.of("{\"overall_summary\":\"요약\"}"));
        when(formatter.format(any(PrReviewResult.class))).thenReturn("복원된 본문");

        service.reviewAsync(EVENT);

        verify(pipeline, never()).run(any(), any());
        verify(githubClient, never()).fetchPullFiles(anyString(), anyInt());
        verify(reviewStore, never()).markAnalyzed(anyString(), any());
        verify(githubClient).createIssueComment("me/repo", 7, "복원된 본문");
        verify(reviewStore).markCompleted(KEY);
        verify(deliveryStore).markReceived("delivery-77");
    }

    @Test
    void 저장된_결과가_깨졌으면_신규리뷰로_진행한다() {
        stubNewReview();
        when(reviewStore.findAnalyzedResult(KEY)).thenReturn(Optional.of("깨진 JSON{{"));

        service.reviewAsync(EVENT);

        verify(pipeline).run(eq(EVENT), any());
        verify(githubClient).createIssueComment("me/repo", 7, "리뷰 본문");
        verify(reviewStore).markCompleted(KEY);
    }

    @Test
    void 결과_직렬화_실패해도_리뷰는_실패하지_않는다() throws Exception {
        // 영속화는 비용 절감용 최적화 — 직렬화가 깨져도 게시와 완료 기록은 진행돼야 함
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonParseException(null, "직렬화 실패"));
        PrReviewService svc = new PrReviewService(githubClient, pipeline, formatter, reviewStore, slackNotifier, deliveryStore,
                new PrReviewProperties(true, 30, 8000, false, 15), failing);
        stubNewReview();

        svc.reviewAsync(EVENT);

        verify(reviewStore).markAnalyzed(KEY, null);
        verify(githubClient).createIssueComment("me/repo", 7, "리뷰 본문");
        verify(reviewStore).markCompleted(KEY);
        verify(slackNotifier, never()).sendPrReviewFailure(any(), any());
    }

    @Test
    void 마커가_이미_있으면_게시를_생략하고_완료처리한다() {
        // 이전 시도에서 게시 성공 후 완료 기록 전에 크래시한 재진입 상황 — 중복 게시가 없어야 함
        stubNewReview();
        when(githubClient.hasIssueCommentWithMarker("me/repo", 7, PrReviewCommentFormatter.MARKER)).thenReturn(true);

        service.reviewAsync(EVENT);

        verify(githubClient, never()).createIssueComment(anyString(), anyInt(), anyString());
        verify(reviewStore).markCompleted(KEY);
        verify(deliveryStore).markReceived("delivery-77");
    }

    @Test
    void 마커_조회_실패시_release하고_ack하지_않는다() {
        // "모르면 게시하지 않는다"(fail-closed) — 조회 실패는 리뷰 실패로 처리해 redelivery가 재시도하게 함
        stubNewReview();
        when(githubClient.hasIssueCommentWithMarker("me/repo", 7, PrReviewCommentFormatter.MARKER))
                .thenThrow(new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR, "코멘트 목록 조회 실패"));

        service.reviewAsync(EVENT);

        verify(githubClient, never()).createIssueComment(anyString(), anyInt(), anyString());
        verify(reviewStore).release(KEY);
        verify(reviewStore, never()).markCompleted(anyString());
        verify(deliveryStore, never()).markReceived(anyString());
        verify(slackNotifier).sendPrReviewFailure(eq(EVENT), any());
    }

    @Test
    void 보조_Slack_실패는_리뷰_완료를_무효화하지_않는다() {
        // PR 코멘트 게시(본질 산출물) 성공 후 보조 채널만 실패한 상황 — 재실행되면 중복 코멘트가 게시되므로 완료 처리돼야 함
        PrReviewProperties slackOn = new PrReviewProperties(true, 30, 8000, true, 15);
        PrReviewService svc = new PrReviewService(githubClient, pipeline, formatter, reviewStore, slackNotifier, deliveryStore, slackOn, objectMapper);
        stubNewReview();
        doThrow(new RuntimeException("slack down")).when(slackNotifier).sendPrReview(any(), any());

        svc.reviewAsync(EVENT);

        verify(githubClient).createIssueComment("me/repo", 7, "리뷰 본문");
        verify(reviewStore).markCompleted(KEY);
        verify(deliveryStore).markReceived("delivery-77");
        verify(reviewStore, never()).release(anyString());
        verify(slackNotifier, never()).sendPrReviewFailure(any(), any());
    }

    @Test
    void 이미_처리중인_PR은_건너뛰고_delivery만_ack한다() {
        when(reviewStore.tryClaim(KEY)).thenReturn(false);

        service.reviewAsync(EVENT);

        verify(pipeline, never()).run(any(), any());
        verify(reviewStore, never()).markCompleted(anyString());
        verify(reviewStore, never()).release(anyString());
        verify(deliveryStore).markReceived("delivery-77");
    }

    // 신규 리뷰 정상 경로의 공통 스텁: 점유 성공 + 변경 파일 1개 + 파이프라인 결과 + 포맷팅
    private PrReviewResult stubNewReview() {
        List<GithubClient.ChangedFile> files = Collections.singletonList(
                GithubClient.ChangedFile.builder().filename("src/Foo.java").status("modified").patch("@@ -1 +1 @@").additions(1).deletions(0).build());
        PrReviewResult result = PrReviewResult.builder().overallSummary("요약").build();
        when(reviewStore.tryClaim(KEY)).thenReturn(true);
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.of(files));
        when(pipeline.run(eq(EVENT), any())).thenReturn(result);
        when(formatter.format(any(PrReviewResult.class))).thenReturn("리뷰 본문");
        return result;
    }
}
