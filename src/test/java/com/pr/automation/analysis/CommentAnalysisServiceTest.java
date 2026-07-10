package com.pr.automation.analysis;

import com.pr.automation.analysis.agent.CommentAnalysisAgent;
import com.pr.automation.analysis.comment.CommentAnalysisService;
import com.pr.automation.analysis.comment.CommentStore;
import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.github.GithubClient;
import com.pr.automation.slack.SlackNotifier;
import com.pr.automation.webhook.recovery.DeliveryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentAnalysisServiceTest {

    private GithubClient githubClient;
    private CommentAnalysisAgent analysisAgent;
    private SlackNotifier slackNotifier;
    private CommentStore store;
    private DeliveryStore deliveryStore;
    private CommentAnalysisService service;

    private static final CommentEvent EVENT = CommentEvent.builder()
            .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
            .repoFullName("me/repo")
            .deliveryId("delivery-555")
            .prNumber(7)
            .prTitle("제목")
            .prBody("본문")
            .headSha("sha1")
            .commentId(555L)
            .commentBody("이렇게 하면 어떨까요?")
            .commentAuthor("reviewer")
            .commentHtmlUrl("https://github.com/me/repo/pull/7#discussion_r555")
            .filePath("src/Foo.java")
            .diffHunk("@@ -1 +1 @@\n+x")
            .line(10)
            .inReplyToId(null)
            .build();

    @BeforeEach
    void setUp() {
        githubClient = mock(GithubClient.class);
        analysisAgent = mock(CommentAnalysisAgent.class);
        slackNotifier = mock(SlackNotifier.class);
        store = mock(CommentStore.class);
        deliveryStore = mock(DeliveryStore.class);
        when(githubClient.isEnabled()).thenReturn(true);
        service = new CommentAnalysisService(githubClient, analysisAgent, slackNotifier, store, deliveryStore);
    }

    @Test
    void 미처리_코멘트는_분석후_Slack전송하고_처리기록한다() {
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변");
        when(store.tryClaim(555L)).thenReturn(true);
        when(analysisAgent.run(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(EVENT);

        verify(analysisAgent).run(any(CommentContext.class), any());
        verify(slackNotifier).send(eq(EVENT), eq(result));
        verify(store).markCompleted(555L);
        verify(deliveryStore).markReceived("delivery-555");
        verify(store, never()).release(anyLong());
        verify(slackNotifier, never()).sendFailure(any(), any());
    }

    @Test
    void 이미_처리한_코멘트는_분석은_안하지만_delivery는_ack한다() {
        when(store.tryClaim(555L)).thenReturn(false);

        service.analyzeAsync(EVENT);

        verify(analysisAgent, never()).run(any(), any());
        verify(slackNotifier, never()).send(any(), any());
        verify(store, never()).markCompleted(anyLong());
        verify(store, never()).release(anyLong());
        verify(deliveryStore).markReceived("delivery-555");
    }

    @Test
    void 분석_실패시_release하고_delivery는_ack하지_않는다() {
        when(store.tryClaim(555L)).thenReturn(true);
        RuntimeException boom = new RuntimeException("LLM down");
        when(analysisAgent.run(any(CommentContext.class), any())).thenThrow(boom);

        service.analyzeAsync(EVENT);

        verify(slackNotifier).sendFailure(EVENT, boom);
        verify(slackNotifier, never()).send(any(), any());
        verify(store).release(555L);
        verify(store, never()).markCompleted(anyLong());
        // 복구 가능하도록 markReceived 호출 안 함
        verify(deliveryStore, never()).markReceived(any());
    }

    @Test
    void 리뷰코멘트는_해당_파일의_전체_patch를_컨텍스트에_담는다() {
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변");
        when(store.tryClaim(555L)).thenReturn(true);
        when(analysisAgent.run(any(CommentContext.class), any())).thenReturn(result);
        String fooPatch = "@@ -1,3 +1,4 @@\n+x\n@@ -50,2 +51,3 @@\n+y";
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Arrays.asList(
                GithubClient.ChangedFile.builder().filename("src/Bar.java").status("modified").patch("@@ bar @@").additions(1).deletions(0).build(),
                GithubClient.ChangedFile.builder().filename("src/Foo.java").status("modified").patch(fooPatch).additions(2).deletions(0).build()));

        service.analyzeAsync(EVENT);

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).run(captor.capture(), any());
        assertThat(captor.getValue().getFilePatch()).isEqualTo(fooPatch);
    }

    @Test
    void 변경파일_목록에_코멘트_파일이_없으면_filePatch는_null이고_분석은_계속된다() {
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변");
        when(store.tryClaim(555L)).thenReturn(true);
        when(analysisAgent.run(any(CommentContext.class), any())).thenReturn(result);
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Arrays.asList(
                GithubClient.ChangedFile.builder().filename("src/Other.java").status("modified").patch("@@ other @@").additions(1).deletions(0).build()));

        service.analyzeAsync(EVENT);

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).run(captor.capture(), any());
        assertThat(captor.getValue().getFilePatch()).isNull();
        verify(slackNotifier).send(eq(EVENT), eq(result));
        verify(store).markCompleted(555L);
    }

    @Test
    void 앵커_정보가_컨텍스트로_그대로_전달된다() {
        CommentEvent anchored = CommentEvent.builder()
                .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
                .repoFullName("me/repo")
                .deliveryId("delivery-558")
                .prNumber(7)
                .headSha("sha1")
                .commentId(558L)
                .commentBody("삭제된 코드에 대한 코멘트")
                .filePath("src/Foo.java")
                .diffHunk("@@ -80,10 +80,2 @@\n-old")
                .line(88)
                .side("LEFT")
                .startLine(85)
                .originalLine(88)
                .build();
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변");
        when(store.tryClaim(558L)).thenReturn(true);
        when(analysisAgent.run(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(anchored);

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).run(captor.capture(), any());
        CommentContext ctx = captor.getValue();
        assertThat(ctx.getLine()).isEqualTo(88);
        assertThat(ctx.getSide()).isEqualTo("LEFT");
        assertThat(ctx.getStartLine()).isEqualTo(85);
        assertThat(ctx.getOriginalLine()).isEqualTo(88);
    }

    @Test
    void issue_comment는_전체_patch를_조회하지_않는다() {
        CommentEvent issue = CommentEvent.builder()
                .eventType(CommentEvent.TYPE_ISSUE_COMMENT)
                .repoFullName("me/repo")
                .deliveryId("delivery-559")
                .prNumber(7)
                .headSha(null)
                .commentId(559L)
                .commentBody("일반 코멘트")
                .build();
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변");
        when(store.tryClaim(559L)).thenReturn(true);
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.of("shaABC"));
        when(analysisAgent.run(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(issue);

        verify(githubClient, never()).fetchPullFiles(anyString(), anyInt());
        verify(store).markCompleted(559L);
    }

    @Test
    void headSha없는_issue_comment는_PR에서_headSha조회후_분석한다() {
        CommentEvent issue = CommentEvent.builder()
                .eventType(CommentEvent.TYPE_ISSUE_COMMENT)
                .repoFullName("me/repo")
                .deliveryId("delivery-556")
                .prNumber(7)
                .prTitle("제목")
                .prBody("본문")
                .headSha(null)
                .commentId(556L)
                .commentBody("일반 코멘트")
                .commentAuthor("user")
                .commentHtmlUrl("url")
                .build();
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변");
        when(store.tryClaim(556L)).thenReturn(true);
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.of("shaABC"));
        when(analysisAgent.run(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(issue);

        verify(analysisAgent).run(any(CommentContext.class), any());
        verify(slackNotifier).send(eq(issue), eq(result));
        verify(store).markCompleted(556L);
        verify(store, never()).release(anyLong());
        verify(deliveryStore).markReceived("delivery-556");
    }

    @Test
    void headSha를_확보할_수_없으면_실패를_알리고_release한다() {
        CommentEvent issue = CommentEvent.builder()
                .eventType(CommentEvent.TYPE_ISSUE_COMMENT)
                .repoFullName("me/repo")
                .deliveryId("delivery-557")
                .prNumber(7)
                .prTitle("제목")
                .prBody("본문")
                .headSha(null)
                .commentId(557L)
                .commentBody("일반 코멘트")
                .commentAuthor("user")
                .commentHtmlUrl("url")
                .build();
        when(store.tryClaim(557L)).thenReturn(true);
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.empty());

        service.analyzeAsync(issue);

        verify(slackNotifier).sendFailure(eq(issue), any());
        verify(analysisAgent, never()).run(any(), any());
        verify(store).release(557L);
        verify(store, never()).markCompleted(anyLong());
        verify(deliveryStore, never()).markReceived(any());
    }
}
