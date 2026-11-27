package com.pr.automation.analysis.comment;

import com.pr.automation.analysis.comment.agent.CommentAnalyzer;
import com.pr.automation.analysis.comment.dto.CommentAnalysisResult;
import com.pr.automation.analysis.comment.dto.CommentContext;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.github.GithubClient;
import com.pr.automation.slack.SlackNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentServiceTest {

    private GithubClient githubClient;
    private CommentAnalyzer analysisAgent;
    private SlackNotifier slackNotifier;
    private CommentRepository commentRepository;
    private com.pr.automation.github.RepoCheckoutFactory checkoutFactory;
    private CommentService service;

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
            .line(10)
            .inReplyToId(null)
            .build();

    @BeforeEach
    void setUp() {
        githubClient = mock(GithubClient.class);
        analysisAgent = mock(CommentAnalyzer.class);
        slackNotifier = mock(SlackNotifier.class);
        commentRepository = mock(CommentRepository.class);
        checkoutFactory = mock(com.pr.automation.github.RepoCheckoutFactory.class);
        // 체크아웃은 실제 git을 타므로 목으로 대체한다 — 이 테스트가 검증할 것은 분석 흐름이다
        com.pr.automation.github.RepoCheckout checkout = mock(com.pr.automation.github.RepoCheckout.class);
        when(checkout.dir()).thenReturn(java.nio.file.Paths.get("."));
        when(checkoutFactory.checkout(any(), any())).thenReturn(checkout);
        when(githubClient.isEnabled()).thenReturn(true);
        service = new CommentService(githubClient, analysisAgent,
                slackNotifier, commentRepository, checkoutFactory);
    }

    @Test
    void 분석결과를_Slack전송_전에_저장한다() {
        CommentAnalysisResult result = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(EVENT);

        InOrder order = inOrder(commentRepository, slackNotifier);
        order.verify(commentRepository).save(argThat(c -> c.getCommentId() == 555L
                && "현 구현 유지 권장".equals(c.getVerdict())
                && "me/repo".equals(c.getRepoFullName())
                && c.getPrNumber() == 7));
        order.verify(slackNotifier).send(eq(EVENT), eq(result));
        verify(slackNotifier, never()).sendFailure(any(), any());
    }

    @Test
    void 이미_분석한_코멘트는_다시_분석하지_않는다() {
        when(commentRepository.existsById(555L)).thenReturn(true);

        service.analyzeAsync(EVENT);

        verify(checkoutFactory, never()).checkout(any(), any());
        verify(analysisAgent, never()).analyze(any(), any());
        verify(commentRepository, never()).save(any());
        verify(slackNotifier, never()).send(any(), any());
        verify(slackNotifier, never()).sendFailure(any(), any());
    }

    @Test
    void 중복_여부_확인이_DB_오류로_실패해도_분석은_진행된다() {
        CommentAnalysisResult result = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
        when(commentRepository.existsById(555L)).thenThrow(new DataAccessResourceFailureException("db down"));
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(EVENT);

        verify(analysisAgent).analyze(any(CommentContext.class), any());
        verify(slackNotifier).send(eq(EVENT), eq(result));
    }

    @Test
    void Slack_전송이_실패해도_분석결과는_저장된다() {
        CommentAnalysisResult result = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);
        doThrow(new RuntimeException("slack down")).when(slackNotifier).send(any(), any());

        service.analyzeAsync(EVENT);

        verify(commentRepository).save(argThat(c -> c.getCommentId() == 555L));
        // Slack이 죽어서 난 실패이므로 Slack으로 실패를 알리지 않는다 (로그만)
        verify(slackNotifier, never()).sendFailure(any(), any());
    }

    @Test
    void DB_저장이_실패해도_Slack은_전송된다() {
        CommentAnalysisResult result = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);
        when(commentRepository.save(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        service.analyzeAsync(EVENT);

        verify(slackNotifier).send(eq(EVENT), eq(result));
    }

    @Test
    void 분석_실패시_실패를_알리고_결과를_저장하지_않는다() {
        RuntimeException boom = new RuntimeException("LLM down");
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenThrow(boom);

        service.analyzeAsync(EVENT);

        verify(slackNotifier).sendFailure(EVENT, boom);
        verify(slackNotifier, never()).send(any(), any());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void 리뷰코멘트는_해당_파일의_전체_patch를_컨텍스트에_담는다() {
        CommentAnalysisResult result = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
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
        CommentAnalysisResult result = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(
                GithubClient.ChangedFile.builder().filename("src/Other.java").status("modified").patch("@@ other @@").additions(1).deletions(0).build())));

        service.analyzeAsync(EVENT);

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).analyze(captor.capture(), any());
        assertThat(captor.getValue().getFilePatch()).isNull();
        verify(slackNotifier).send(eq(EVENT), eq(result));
        verify(commentRepository).save(argThat(c -> c.getCommentId() == 555L));
    }

    @Test
    void 변경파일_조회가_실패해도_filePatch_없이_분석은_계속된다() {
        // patch는 보조 맥락이므로 조회 실패(empty)를 실패로 승격하지 않음 — PR 리뷰 경로와 다른 점
        CommentAnalysisResult result = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);
        when(githubClient.fetchPullFiles("me/repo", 7)).thenReturn(Optional.empty());

        service.analyzeAsync(EVENT);

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).analyze(captor.capture(), any());
        assertThat(captor.getValue().getFilePatch()).isNull();
        verify(slackNotifier).send(eq(EVENT), eq(result));
        verify(commentRepository).save(argThat(c -> c.getCommentId() == 555L));
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
                .line(88)
                .side("LEFT")
                .startLine(85)
                .originalLine(88)
                .build();
        CommentAnalysisResult result = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
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
        CommentAnalysisResult result = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.of("shaABC"));
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(issue);

        verify(githubClient, never()).fetchPullFiles(anyString(), anyInt());
        verify(commentRepository).save(argThat(c -> c.getCommentId() == 559L));
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
        CommentAnalysisResult result = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.of("shaABC"));
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(result);

        service.analyzeAsync(issue);

        verify(analysisAgent).analyze(any(CommentContext.class), any());
        verify(slackNotifier).send(eq(issue), eq(result));
        verify(commentRepository).save(argThat(c -> c.getCommentId() == 556L));
    }

    @Test
    void headSha를_확보할_수_없으면_실패를_알리고_결과를_저장하지_않는다() {
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
        when(githubClient.fetchPullHeadSha("me/repo", 7)).thenReturn(Optional.empty());

        service.analyzeAsync(issue);

        verify(slackNotifier).sendFailure(eq(issue), any());
        verify(analysisAgent, never()).analyze(any(), any());
        verify(commentRepository, never()).save(any());
    }

    // ---- 리뷰 단위 묶음 분석 ----

    private static final long REVIEW_ID = 9000L;

    private static final CommentEvent REVIEW = CommentEvent.builder()
            .eventType(CommentEvent.TYPE_REVIEW_BODY)
            .repoFullName("me/repo")
            .prNumber(7)
            .prTitle("제목")
            .prBody("본문")
            .headSha("sha1")
            .commentId(REVIEW_ID)
            .commentBody("전반적으로 상태 문자열 비교가 많습니다")
            .commentAuthor("reviewer")
            .reviewState("commented")
            .build();

    private static CommentEvent reviewWithoutBody() {
        return CommentEvent.builder().eventType(CommentEvent.TYPE_REVIEW_BODY).repoFullName("me/repo").prNumber(7)
                .headSha("sha1").commentId(REVIEW_ID).commentBody("").commentAuthor("reviewer").build();
    }

    private static GithubClient.FetchedComment inline(long id, Long reviewId) {
        return GithubClient.FetchedComment.builder().id(id).author("reviewer").body("인라인 " + id)
                .pullRequestReviewId(reviewId).filePath("src/Foo.java").line(10).side("RIGHT").build();
    }

    private static CommentAnalysisResult okResult() {
        return new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변", null);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<CommentContext>> contextListCaptor() {
        return ArgumentCaptor.forClass((Class<List<CommentContext>>) (Class<?>) List.class);
    }

    @Test
    void 리뷰의_코멘트_여러_건은_LLM_1회로_묶어_분석하고_코멘트별로_저장한다() {
        when(githubClient.fetchPullReviewComments("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(
                inline(702L, REVIEW_ID), inline(701L, REVIEW_ID), inline(800L, 1234L)))); // 800은 다른 리뷰의 코멘트
        Map<Long, CommentAnalysisResult> results = new HashMap<>();
        results.put(701L, okResult());
        results.put(702L, okResult());
        results.put(REVIEW_ID, okResult());
        when(analysisAgent.analyzeBatch(any(), any())).thenReturn(results);

        service.analyzeReviewAsync(REVIEW);

        ArgumentCaptor<List<CommentContext>> captor = contextListCaptor();
        verify(analysisAgent).analyzeBatch(captor.capture(), any());
        // 작성 순(id 오름차순) 인라인 코멘트 뒤에 총평 - 다른 리뷰의 코멘트는 섞이지 않는다
        assertThat(captor.getValue()).extracting(CommentContext::getCommentId).containsExactly(701L, 702L, REVIEW_ID);
        verify(analysisAgent, never()).analyze(any(), any());
        // 체크아웃도 묶음당 1회
        verify(checkoutFactory, times(1)).checkout(any(), any());
        verify(commentRepository, times(3)).save(any());
        verify(slackNotifier, times(3)).send(any(), any());
    }

    @Test
    void 리뷰에_분석할_코멘트가_1건이면_단건_경로로_분석한다() {
        // 단일 코멘트나 답글을 달면 GitHub이 본문 없는 리뷰를 하나씩 만든다
        when(githubClient.fetchPullReviewComments("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(inline(701L, REVIEW_ID))));
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(okResult());

        service.analyzeReviewAsync(reviewWithoutBody());

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).analyze(captor.capture(), any());
        assertThat(captor.getValue().getCommentId()).isEqualTo(701L);
        assertThat(captor.getValue().getEventType()).isEqualTo(CommentEvent.TYPE_REVIEW_COMMENT);
        verify(analysisAgent, never()).analyzeBatch(any(), any());
        verify(commentRepository).save(argThat(c -> c.getCommentId() == 701L));
    }

    @Test
    void 리뷰_코멘트의_위치_정보가_그대로_전달된다() {
        GithubClient.FetchedComment left = GithubClient.FetchedComment.builder().id(701L).author("reviewer").body("삭제된 코드")
                .pullRequestReviewId(REVIEW_ID).htmlUrl("url-701").filePath("src/Foo.java")
                .line(88).side("LEFT").startLine(85).originalLine(88).subjectType("line").inReplyToId(600L).build();
        GithubClient.FetchedComment file = GithubClient.FetchedComment.builder().id(702L).author("reviewer").body("파일 전체")
                .pullRequestReviewId(REVIEW_ID).filePath("src/Bar.java").line(1).subjectType("file").build();
        when(githubClient.fetchPullReviewComments("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(left, file)));
        when(analysisAgent.analyzeBatch(any(), any())).thenReturn(new HashMap<>());

        service.analyzeReviewAsync(reviewWithoutBody());

        ArgumentCaptor<List<CommentContext>> captor = contextListCaptor();
        verify(analysisAgent).analyzeBatch(captor.capture(), any());
        CommentContext l = captor.getValue().get(0);
        assertThat(l.getLine()).isEqualTo(88);
        assertThat(l.getSide()).isEqualTo("LEFT");
        assertThat(l.getStartLine()).isEqualTo(85);
        assertThat(l.getOriginalLine()).isEqualTo(88);
        assertThat(l.getHeadSha()).isEqualTo("sha1");
        // GitHub이 파일 코멘트에도 line=1을 보내므로 subject_type으로 판별해야 한다
        assertThat(l.isFileLevel()).isFalse();
        assertThat(captor.getValue().get(1).isFileLevel()).isTrue();
    }

    @Test
    void 묶음_응답에서_빠진_코멘트는_실패로_알리고_저장하지_않는다() {
        when(githubClient.fetchPullReviewComments("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(
                inline(701L, REVIEW_ID), inline(702L, REVIEW_ID))));
        Map<Long, CommentAnalysisResult> results = new HashMap<>();
        results.put(701L, okResult());
        when(analysisAgent.analyzeBatch(any(), any())).thenReturn(results);

        service.analyzeReviewAsync(reviewWithoutBody());

        verify(commentRepository).save(argThat(c -> c.getCommentId() == 701L));
        verify(commentRepository, never()).save(argThat(c -> c.getCommentId() == 702L));
        // 저장하지 않았으므로 재전송하면 702만 다시 분석된다
        verify(slackNotifier).sendFailure(argThat(e -> e.getCommentId() == 702L), any());
    }

    @Test
    void 묶음_분석이_실패하면_모든_코멘트의_실패를_알린다() {
        when(githubClient.fetchPullReviewComments("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(
                inline(701L, REVIEW_ID), inline(702L, REVIEW_ID))));
        when(analysisAgent.analyzeBatch(any(), any())).thenThrow(new RuntimeException("LLM down"));

        service.analyzeReviewAsync(reviewWithoutBody());

        verify(slackNotifier, times(2)).sendFailure(any(), any());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void 이미_분석한_코멘트는_묶음에서_빠진다() {
        when(githubClient.fetchPullReviewComments("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(
                inline(701L, REVIEW_ID), inline(702L, REVIEW_ID), inline(703L, REVIEW_ID))));
        when(commentRepository.existsById(701L)).thenReturn(true);
        when(analysisAgent.analyzeBatch(any(), any())).thenReturn(new HashMap<>());

        service.analyzeReviewAsync(reviewWithoutBody());

        ArgumentCaptor<List<CommentContext>> captor = contextListCaptor();
        verify(analysisAgent).analyzeBatch(captor.capture(), any());
        assertThat(captor.getValue()).extracting(CommentContext::getCommentId).containsExactly(702L, 703L);
    }

    @Test
    void 묶음_상한을_넘는_리뷰는_나눠서_분석한다() {
        List<GithubClient.FetchedComment> many = new ArrayList<>();
        for (long id = 1; id <= CommentService.MAX_BATCH_SIZE + 2; id++) {
            many.add(inline(id, REVIEW_ID));
        }
        when(githubClient.fetchPullReviewComments("me/repo", 7)).thenReturn(Optional.of(many));
        when(analysisAgent.analyzeBatch(any(), any())).thenReturn(new HashMap<>());

        service.analyzeReviewAsync(reviewWithoutBody());

        ArgumentCaptor<List<CommentContext>> captor = contextListCaptor();
        verify(analysisAgent, times(2)).analyzeBatch(captor.capture(), any());
        assertThat(captor.getAllValues().get(0)).hasSize(CommentService.MAX_BATCH_SIZE);
        assertThat(captor.getAllValues().get(1)).hasSize(2);
    }

    @Test
    void 리뷰에_분석할_코멘트가_없으면_아무것도_하지_않는다() {
        // Approve만 누른 리뷰
        when(githubClient.fetchPullReviewComments("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(inline(800L, 1234L))));

        service.analyzeReviewAsync(reviewWithoutBody());

        verify(checkoutFactory, never()).checkout(any(), any());
        verify(analysisAgent, never()).analyze(any(), any());
        verify(analysisAgent, never()).analyzeBatch(any(), any());
    }

    @Test
    void 리뷰_코멘트_목록_조회에_실패하면_실패를_알린다() {
        // 빈 목록으로 삼키면 인라인 코멘트가 조용히 누락된다
        when(githubClient.fetchPullReviewComments("me/repo", 7)).thenReturn(Optional.empty());

        service.analyzeReviewAsync(REVIEW);

        verify(slackNotifier).sendFailure(eq(REVIEW), any());
        verify(analysisAgent, never()).analyzeBatch(any(), any());
    }

    // ---- PR 작성자 표시 ----

    @Test
    void 답글의_이전_스레드에서_PR_작성자의_말에만_표시를_붙인다() {
        CommentEvent review = CommentEvent.builder().eventType(CommentEvent.TYPE_REVIEW_BODY).repoFullName("me/repo").prNumber(7)
                .prAuthor("MyName").headSha("sha1").commentId(REVIEW_ID).commentBody("").commentAuthor("reviewer").build();
        GithubClient.FetchedComment root = GithubClient.FetchedComment.builder().id(600L).author("reviewer")
                .body("A 부분 아니지 않나요?").pullRequestReviewId(1L).filePath("src/Foo.java").line(10).build();
        GithubClient.FetchedComment authorReply = GithubClient.FetchedComment.builder().id(601L).author("myname")
                .body("이런 이유로 맞습니다").inReplyToId(600L).pullRequestReviewId(2L).filePath("src/Foo.java").line(10).build();
        GithubClient.FetchedComment target = GithubClient.FetchedComment.builder().id(602L).author("reviewer")
                .body("아 그렇네요 감사합니다").inReplyToId(600L).pullRequestReviewId(REVIEW_ID).filePath("src/Foo.java").line(10).build();
        when(githubClient.fetchPullReviewComments("me/repo", 7)).thenReturn(Optional.of(Arrays.asList(root, authorReply, target)));
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(okResult());

        service.analyzeReviewAsync(review);

        ArgumentCaptor<CommentContext> captor = ArgumentCaptor.forClass(CommentContext.class);
        verify(analysisAgent).analyze(captor.capture(), any());
        CommentContext ctx = captor.getValue();
        // 로그인 대소문자가 달라도 같은 사람으로 본다
        assertThat(ctx.getParentComments()).containsExactly(
                "@reviewer: A 부분 아니지 않나요?",
                "@myname (PR 작성자): 이런 이유로 맞습니다");
        assertThat(ctx.getPrAuthor()).isEqualTo("MyName");
        assertThat(ctx.getCommentAuthor()).isEqualTo("reviewer");
    }

    // ---- 분석 생략 ----

    @Test
    void 분석_생략_판정도_저장하고_알린다() {
        // 저장해야 재전송 시 다시 분석하지 않고, 알려야 잘못 생략한 코멘트를 사람이 알아챌 수 있다
        CommentAnalysisResult skipped = new CommentAnalysisResult("", "", "", "분석 생략 - 감사 인사", "근거", "", null);
        when(analysisAgent.analyze(any(CommentContext.class), any())).thenReturn(skipped);

        service.analyzeAsync(EVENT);

        verify(commentRepository).save(argThat(c -> c.getCommentId() == 555L && c.getVerdict().startsWith("분석 생략")));
        verify(slackNotifier).send(EVENT, skipped);
    }
}
