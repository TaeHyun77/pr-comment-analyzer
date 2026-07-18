package com.pr.automation.review;

import com.pr.automation.config.PrReviewProperties;
import com.pr.automation.github.GithubClient;
import com.pr.automation.review.agent.PrReviewPipeline;
import com.pr.automation.review.dto.PrReviewEvent;
import com.pr.automation.review.dto.PrReviewResult;
import com.pr.automation.slack.SlackNotifier;
import com.pr.automation.webhook.recovery.DeliveryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    private PrReviewService service;

    @BeforeEach
    void setUp() {
        githubClient = mock(GithubClient.class);
        pipeline = mock(PrReviewPipeline.class);
        formatter = mock(PrReviewCommentFormatter.class);
        reviewStore = mock(PrReviewStore.class);
        slackNotifier = mock(SlackNotifier.class);
        deliveryStore = mock(DeliveryStore.class);
        PrReviewProperties properties = new PrReviewProperties(true, 30, 8000, false);
        when(githubClient.isEnabled()).thenReturn(true);
        service = new PrReviewService(githubClient, pipeline, formatter, reviewStore, slackNotifier, deliveryStore, properties);
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
        List<GithubClient.ChangedFile> files = Collections.singletonList(
                GithubClient.ChangedFile.builder().filename("src/Foo.java").status("modified").patch("@@ -1 +1 @@").additions(1).deletions(0).build());
        PrReviewResult result = mock(PrReviewResult.class);
        when(reviewStore.tryClaim(KEY)).thenReturn(true);
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.of(files));
        when(pipeline.run(eq(EVENT), eq(files))).thenReturn(result);
        when(formatter.format(result)).thenReturn("리뷰 본문");

        service.reviewAsync(EVENT);

        verify(githubClient).createIssueComment("me/repo", 7, "리뷰 본문");
        verify(reviewStore).markCompleted(KEY);
        verify(deliveryStore).markReceived("delivery-77");
        verify(reviewStore, never()).release(anyString());
        verify(slackNotifier, never()).sendPrReviewFailure(any(), any());
    }

    @Test
    void 보조_Slack_실패는_리뷰_완료를_무효화하지_않는다() {
        // PR 코멘트 게시(본질 산출물) 성공 후 보조 채널만 실패한 상황 — 재실행되면 중복 코멘트가 게시되므로 완료 처리돼야 함
        PrReviewProperties slackOn = new PrReviewProperties(true, 30, 8000, true);
        PrReviewService svc = new PrReviewService(githubClient, pipeline, formatter, reviewStore, slackNotifier, deliveryStore, slackOn);
        List<GithubClient.ChangedFile> files = Collections.singletonList(
                GithubClient.ChangedFile.builder().filename("src/Foo.java").status("modified").patch("@@ -1 +1 @@").additions(1).deletions(0).build());
        PrReviewResult result = mock(PrReviewResult.class);
        when(reviewStore.tryClaim(KEY)).thenReturn(true);
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.of(files));
        when(pipeline.run(eq(EVENT), eq(files))).thenReturn(result);
        when(formatter.format(result)).thenReturn("리뷰 본문");
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
}
