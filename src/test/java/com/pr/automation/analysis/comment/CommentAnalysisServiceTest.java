package com.pr.automation.analysis.comment;

import com.pr.automation.analysis.comment.CommentAnalysisService;
import com.pr.automation.analysis.comment.CommentStore;
import com.pr.automation.analysis.comment.agent.CommentAnalysisAgent;
import com.pr.automation.analysis.comment.dto.AnalysisResult;
import com.pr.automation.analysis.comment.dto.CommentContext;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
import com.pr.automation.github.GithubClient;
import com.pr.automation.llm.dto.LlmUsage;
import com.pr.automation.slack.SlackNotifier;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentAnalysisServiceTest {

    private GithubClient githubClient;
    private CommentAnalysisAgent analysisAgent;
    private SlackNotifier slackNotifier;
    private CommentStore store;
    private com.pr.automation.github.RepoCheckoutFactory checkoutFactory;
    private CommentAnalysisService service;

    private static final CommentEvent EVENT = CommentEvent.builder()
            .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
            .repoFullName("me/repo")
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
        checkoutFactory = mock(com.pr.automation.github.RepoCheckoutFactory.class);
        // 체크아웃은 실제 git을 타므로 목으로 대체한다 — 이 테스트가 검증할 것은 분석 흐름이다
        com.pr.automation.github.RepoCheckout checkout = mock(com.pr.automation.github.RepoCheckout.class);
        when(checkout.dir()).thenReturn(java.nio.file.Paths.get("."));
        when(checkoutFactory.checkout(any(), any())).thenReturn(checkout);
        when(githubClient.isEnabled()).thenReturn(true);
        service = new CommentAnalysisService(githubClient, analysisAgent,
                slackNotifier, store, new com.fasterxml.jackson.databind.ObjectMapper(), checkoutFactory);
    }

    @Test
    void 미처리_코멘트는_분석후_Slack전송하고_처리기록한다() {
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null, null, null, null);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(EVENT);

        verify(analysisAgent).analyze(any(CommentContext.class), any());
        verify(slackNotifier).send(eq(EVENT), eq(result));
        verify(store).markCompleted(555L);
        verify(store, never()).markFailed(anyLong());
        verify(slackNotifier, never()).sendFailure(any(), any());
    }

    @Test
    void 점유에_실패하면_아무_작업도_하지_않는다() {
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(false);

        service.analyzeAsync(EVENT);

        verify(analysisAgent, never()).analyze(any(), any());
        verify(slackNotifier, never()).send(any(), any());
        verify(store, never()).markCompleted(anyLong());
        verify(store, never()).markFailed(anyLong());
    }

    @Test
    void 분석_실패시_markFailed한다() {
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        RuntimeException boom = new RuntimeException("LLM down");
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenThrow(boom);

        service.analyzeAsync(EVENT);

        verify(slackNotifier).sendFailure(EVENT, boom);
        verify(slackNotifier, never()).send(any(), any());
        verify(store).markFailed(555L);
        verify(store, never()).markCompleted(anyLong());
    }

    @Test
    void 리뷰코멘트는_해당_파일의_전체_patch를_컨텍스트에_담는다() {
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null, null, null, null);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);
        String fooPatch = "@@ -1,3 +1,4 @@\n+x\n@@ -50,2 +51,3 @@\n+y";
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(
                GithubClient.ChangedFile.builder().filename("src/Bar.java").status("modified").patch("@@ bar @@").additions(1).deletions(0).build(),
                GithubClient.ChangedFile.builder().filename("src/Foo.java").status("modified").patch(fooPatch).additions(2).deletions(0).build())));

        service.analyzeAsync(EVENT);

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).analyze(captor.capture(), any());
        assertThat(captor.getValue().getFilePatch()).isEqualTo(fooPatch);
    }

    @Test
    void 변경파일_목록에_코멘트_파일이_없으면_filePatch는_null이고_분석은_계속된다() {
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null, null, null, null);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(
                GithubClient.ChangedFile.builder().filename("src/Other.java").status("modified").patch("@@ other @@").additions(1).deletions(0).build())));

        service.analyzeAsync(EVENT);

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).analyze(captor.capture(), any());
        assertThat(captor.getValue().getFilePatch()).isNull();
        verify(slackNotifier).send(eq(EVENT), eq(result));
        verify(store).markCompleted(555L);
    }

    @Test
    void 분석성공후_Slack실패시_결과를_저장하고_완료처리는_하지_않는다() {
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null, null, null, null);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);
        doThrow(new RuntimeException("slack down")).when(slackNotifier).send(any(), any());

        service.analyzeAsync(EVENT);

        // markAnalyzed가 send보다 먼저 호출되어 결과가 저장돼 있어야 함 — 재진입 시 통지만 재시도 가능
        verify(store).markAnalyzed(eq(555L), anyString());
        verify(store, never()).markCompleted(anyLong());
        // 통지 실패는 FAILED로 강등하지 않는다 — ANALYZED로 남아야 재시도 때 재분석 없이 통지만 다시 한다
        verify(store, never()).markFailed(anyLong());
        // Slack이 죽어서 난 실패이므로 Slack으로 실패를 알리지 않는다 (로그만)
        verify(slackNotifier, never()).sendFailure(any(), any());
    }

    @Test
    void 저장된_분석결과가_있으면_LLM없이_통지만_재시도한다() throws Exception {
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null, null, null, null);
        String savedJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(store.findAnalyzedResult(555L)).thenReturn(Optional.of(savedJson));

        service.analyzeAsync(EVENT);

        verify(analysisAgent, never()).analyze(any(), any());
        verify(slackNotifier).send(eq(EVENT), any(AnalysisResult.class));
        verify(store).markCompleted(555L);
    }

    @Test
    void 저장된_결과의_토큰_사용량과_예산이_복원된다() throws Exception {
        // 통지 재시도 시 사용량 표기가 사라지지 않아야 한다 — LlmUsage가 불변 객체라 역직렬화 경로가 별도로 필요함
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변",
                3, 2, new LlmUsage(100L, 200L, 300L, 40L, 1706L, 0.0758), 400000L);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String savedJson = mapper.writeValueAsString(result);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(store.findAnalyzedResult(555L)).thenReturn(Optional.of(savedJson));

        service.analyzeAsync(EVENT);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(slackNotifier).send(eq(EVENT), captor.capture());
        AnalysisResult restored = captor.getValue();

        assertThat(restored.getUsage()).isNotNull();
        assertThat(restored.getUsage().getTotalTokens()).isEqualTo(640L);
        assertThat(restored.getUsage().getCostUsd()).isEqualTo(0.0758);
        assertThat(restored.getTokenBudget()).isEqualTo(400000L);
        assertThat(restored.getRoundsUsed()).isEqualTo(3);
    }

    @Test
    void 저장된_결과가_깨졌으면_신규_분석을_수행한다() {
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null, null, null, null);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(store.findAnalyzedResult(555L)).thenReturn(Optional.of("{깨진 json"));
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(EVENT);

        verify(analysisAgent).analyze(any(CommentContext.class), any());
        verify(store).markAnalyzed(eq(555L), anyString());
        verify(store).markCompleted(555L);
    }

    @Test
    void 변경파일_조회가_실패해도_filePatch_없이_분석은_계속된다() {
        // patch는 보조 맥락이므로 조회 실패(empty)를 실패로 승격하지 않음 — PR 리뷰 경로와 다른 점
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null, null, null, null);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.empty());

        service.analyzeAsync(EVENT);

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).analyze(captor.capture(), any());
        assertThat(captor.getValue().getFilePatch()).isNull();
        verify(slackNotifier).send(eq(EVENT), eq(result));
        verify(store).markCompleted(555L);
        verify(store, never()).markFailed(anyLong());
    }

    @Test
    void 앵커_정보가_컨텍스트로_그대로_전달된다() {
        CommentEvent anchored = CommentEvent.builder()
                .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
                .repoFullName("me/repo")
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
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null, null, null, null);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(anchored);

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).analyze(captor.capture(), any());
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
                .prNumber(7)
                .headSha(null)
                .commentId(559L)
                .commentBody("일반 코멘트")
                .build();
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null, null, null, null);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.of("shaABC"));
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(issue);

        verify(githubClient, never()).fetchPullFiles(anyString(), anyInt());
        verify(store).markCompleted(559L);
    }

    @Test
    void headSha없는_issue_comment는_PR에서_headSha조회후_분석한다() {
        CommentEvent issue = CommentEvent.builder()
                .eventType(CommentEvent.TYPE_ISSUE_COMMENT)
                .repoFullName("me/repo")
                .prNumber(7)
                .prTitle("제목")
                .prBody("본문")
                .headSha(null)
                .commentId(556L)
                .commentBody("일반 코멘트")
                .commentAuthor("user")
                .commentHtmlUrl("url")
                .build();
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null, null, null, null);
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.of("shaABC"));
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(issue);

        verify(analysisAgent).analyze(any(CommentContext.class), any());
        verify(slackNotifier).send(eq(issue), eq(result));
        verify(store).markCompleted(556L);
        verify(store, never()).markFailed(anyLong());
    }

    @Test
    void headSha를_확보할_수_없으면_실패를_알리고_markFailed한다() {
        CommentEvent issue = CommentEvent.builder()
                .eventType(CommentEvent.TYPE_ISSUE_COMMENT)
                .repoFullName("me/repo")
                .prNumber(7)
                .prTitle("제목")
                .prBody("본문")
                .headSha(null)
                .commentId(557L)
                .commentBody("일반 코멘트")
                .commentAuthor("user")
                .commentHtmlUrl("url")
                .build();
        when(store.tryClaim(any(CommentEvent.class))).thenReturn(true);
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.empty());

        service.analyzeAsync(issue);

        verify(slackNotifier).sendFailure(eq(issue), any());
        verify(analysisAgent, never()).analyze(any(), any());
        verify(store).markFailed(557L);
        verify(store, never()).markCompleted(anyLong());
    }
}
