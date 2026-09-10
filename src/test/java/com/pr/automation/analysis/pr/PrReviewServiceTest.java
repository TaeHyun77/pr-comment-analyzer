package com.pr.automation.analysis.pr;

import com.pr.automation.analysis.pr.agent.PrReviewAgent;
import com.pr.automation.analysis.pr.dto.PrReviewEvent;
import com.pr.automation.analysis.pr.dto.PrReviewResult;
import com.pr.automation.config.properties.PrReviewProperties;
import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import com.pr.automation.github.GithubClient;
import com.pr.automation.github.RepoCheckout;
import com.pr.automation.github.RepoCheckoutFactory;
import com.pr.automation.slack.SlackNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.file.Paths;
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
            .repoFullName("me/repo")
            .prNumber(7)
            .prTitle("제목")
            .build();

    private GithubClient githubClient;
    private PrReviewAgent reviewAgent;
    private PrReviewCommentFormatter formatter;
    private RepoCheckoutFactory checkoutFactory;
    private SlackNotifier slackNotifier;
    private PrReviewService service;

    @BeforeEach
    void setUp() {
        githubClient = mock(GithubClient.class);
        reviewAgent = mock(PrReviewAgent.class);
        formatter = mock(PrReviewCommentFormatter.class);
        checkoutFactory = mock(RepoCheckoutFactory.class);
        RepoCheckout checkout = mock(RepoCheckout.class);
        when(checkout.dir()).thenReturn(Paths.get("."));
        when(checkoutFactory.checkout(any(), any())).thenReturn(checkout);
        slackNotifier = mock(SlackNotifier.class);
        when(githubClient.isEnabled()).thenReturn(true);
        service = newService(new PrReviewProperties(true, 30, 8000, false));
    }

    @Test
    void 정상경로는_리뷰결과를_PR코멘트로_게시한다() {
        stubNewReview();

        service.reviewAsync(EVENT);

        verify(githubClient).createIssueComment("me/repo", 7, "리뷰 본문");
        verify(slackNotifier, never()).sendPrReviewFailure(any(), any());
    }

    @Test
    void 마커가_이미_있으면_리뷰도_게시도_하지_않는다() {
        // 웹훅이 다시 들어온 상황 — 상태 저장이 없으므로 마커가 유일한 중복 방지책이며, LLM 호출 전에 걸러야 한다
        stubNewReview();
        when(githubClient.hasIssueCommentWithMarker("me/repo", 7, PrReviewCommentFormatter.MARKER)).thenReturn(true);

        service.reviewAsync(EVENT);

        verify(githubClient, never()).fetchPullFiles(anyString(), anyInt());
        verify(reviewAgent, never()).review(any(), any(), any());
        verify(githubClient, never()).createIssueComment(anyString(), anyInt(), anyString());
    }

    @Test
    void 마커_조회_실패시_게시하지_않고_실패를_알린다() {
        // "모르면 게시하지 않는다"(fail-closed)
        stubNewReview();
        when(githubClient.hasIssueCommentWithMarker("me/repo", 7, PrReviewCommentFormatter.MARKER))
                .thenThrow(new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR, "코멘트 목록 조회 실패"));

        service.reviewAsync(EVENT);

        verify(reviewAgent, never()).review(any(), any(), any());
        verify(githubClient, never()).createIssueComment(anyString(), anyInt(), anyString());
        verify(slackNotifier).sendPrReviewFailure(eq(EVENT), any());
    }

    @Test
    void 변경파일_조회_실패시_실패를_알린다() {
        // 조회 실패(empty)는 "변경 없음"과 달리 실패로 다뤄야 원인을 알 수 있다
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.empty());

        service.reviewAsync(EVENT);

        verify(reviewAgent, never()).review(any(), any(), any());
        verify(githubClient, never()).createIssueComment(anyString(), anyInt(), anyString());
        verify(slackNotifier).sendPrReviewFailure(eq(EVENT), any());
    }

    @Test
    void 변경파일이_정말_없으면_리뷰를_생략한다() {
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.of(Collections.emptyList()));

        service.reviewAsync(EVENT);

        verify(reviewAgent, never()).review(any(), any(), any());
        verify(githubClient, never()).createIssueComment(anyString(), anyInt(), anyString());
        verify(slackNotifier, never()).sendPrReviewFailure(any(), any());
    }

    @Test
    void 리뷰_도중_실패하면_게시하지_않고_실패를_알린다() {
        stubNewReview();
        when(reviewAgent.review(any(), any(), any())).thenThrow(new RuntimeException("에이전트 실패"));

        service.reviewAsync(EVENT);

        verify(githubClient, never()).createIssueComment(anyString(), anyInt(), anyString());
        verify(slackNotifier).sendPrReviewFailure(eq(EVENT), any());
    }

    @Test
    void 보조_Slack_실패는_게시를_무효화하지_않는다() {
        // PR 코멘트 게시(본질 산출물)가 끝난 뒤라 실패로 되돌릴 수 없다
        PrReviewService svc = newService(new PrReviewProperties(true, 30, 8000, true));
        stubNewReview();
        doThrow(new RuntimeException("slack down")).when(slackNotifier).sendPrReview(any(), any());

        svc.reviewAsync(EVENT);

        verify(githubClient).createIssueComment("me/repo", 7, "리뷰 본문");
        verify(slackNotifier, never()).sendPrReviewFailure(any(), any());
    }

    private PrReviewService newService(PrReviewProperties properties) {
        return new PrReviewService(githubClient, reviewAgent, formatter, slackNotifier, properties, checkoutFactory);
    }

    // 신규 리뷰 정상 경로의 공통 스텁: 변경 파일 1개 + 에이전트 결과 + 포맷팅
    private void stubNewReview() {
        List<GithubClient.ChangedFile> files = Collections.singletonList(
                GithubClient.ChangedFile.builder().filename("src/Foo.java").status("modified").patch("@@ -1 +1 @@").additions(1).deletions(0).build());
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.of(files));
        when(reviewAgent.review(eq(EVENT), any(), any())).thenReturn(PrReviewResult.builder().overallSummary("요약").build());
        when(formatter.format(any(PrReviewResult.class))).thenReturn("리뷰 본문");
    }
}
