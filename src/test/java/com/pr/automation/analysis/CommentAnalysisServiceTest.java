package com.pr.automation.analysis;

import com.pr.automation.analysis.agent.CommentAnalysisAgent;
import com.pr.automation.analysis.comment.CommentAnalysisService;
import com.pr.automation.analysis.comment.CommentStore;
import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.github.GithubClient;
import com.pr.automation.slack.SlackNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommentAnalysisServiceTest {

    private GithubClient githubClient;
    private CommentAnalysisAgent analysisAgent;
    private SlackNotifier slackNotifier;
    private CommentStore store;
    private CommentAnalysisService service;

    private static final CommentEvent EVENT = new CommentEvent(
            CommentEvent.TYPE_REVIEW_COMMENT, "me/repo", 7, "제목", "본문", "sha1",
            555L, "이렇게 하면 어떨까요?", "reviewer", "https://github.com/me/repo/pull/7#discussion_r555",
            "src/Foo.java", "@@ -1 +1 @@\n+x", 10, null);

    @BeforeEach
    void setUp() {
        githubClient = mock(GithubClient.class);
        analysisAgent = mock(CommentAnalysisAgent.class);
        slackNotifier = mock(SlackNotifier.class);
        store = mock(CommentStore.class);
        when(githubClient.isEnabled()).thenReturn(true);
        service = new CommentAnalysisService(githubClient, analysisAgent, slackNotifier, store);
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
        verify(store).save();
        verify(store, never()).release(anyLong());
        verify(slackNotifier, never()).sendFailure(any(), any());
    }

    @Test
    void 이미_처리한_코멘트는_아무것도_하지_않는다() {
        when(store.tryClaim(555L)).thenReturn(false);

        service.analyzeAsync(EVENT);

        verifyNoInteractions(analysisAgent, slackNotifier);
        verify(store, never()).markCompleted(anyLong());
        verify(store, never()).release(anyLong());
    }

    @Test
    void 분석_실패시_Slack에_실패를_알리고_release하며_처리기록은_하지_않는다() {
        when(store.tryClaim(555L)).thenReturn(true);
        RuntimeException boom = new RuntimeException("LLM down");
        when(analysisAgent.run(any(CommentContext.class), any())).thenThrow(boom);

        service.analyzeAsync(EVENT);

        verify(slackNotifier).sendFailure(EVENT, boom);
        verify(slackNotifier, never()).send(any(), any());
        verify(store).release(555L);
        verify(store, never()).markCompleted(anyLong());
        verify(store, never()).save();
    }

    @Test
    void headSha없는_issue_comment는_PR에서_headSha조회후_분석한다() {
        CommentEvent issue = new CommentEvent(
                CommentEvent.TYPE_ISSUE_COMMENT, "me/repo", 7, "제목", "본문", null,
                556L, "일반 코멘트", "user", "url", null, null, null, null);
        AnalysisResult result = new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변");
        when(store.tryClaim(556L)).thenReturn(true);
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.of("shaABC"));
        when(analysisAgent.run(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(issue);

        verify(analysisAgent).run(any(CommentContext.class), any());
        verify(slackNotifier).send(eq(issue), eq(result));
        verify(store).markCompleted(556L);
        verify(store, never()).release(anyLong());
    }

    @Test
    void headSha를_확보할_수_없으면_실패를_알리고_release한다() {
        CommentEvent issue = new CommentEvent(
                CommentEvent.TYPE_ISSUE_COMMENT, "me/repo", 7, "제목", "본문", null,
                557L, "일반 코멘트", "user", "url", null, null, null, null);
        when(store.tryClaim(557L)).thenReturn(true);
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.empty());

        service.analyzeAsync(issue);

        verify(slackNotifier).sendFailure(eq(issue), any());
        verify(analysisAgent, never()).run(any(), any());
        verify(store).release(557L);
        verify(store, never()).markCompleted(anyLong());
    }
}
