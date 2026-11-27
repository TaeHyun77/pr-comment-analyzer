package com.pr.automation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.CommentService;
import com.pr.automation.analysis.comment.CommentRepository;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.config.properties.GithubProperties;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
import com.pr.automation.slack.SlackNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookEventHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommentService analysisService;
    private CommentRepository commentRepository;
    private SlackNotifier slackNotifier;
    private WebhookEventHandler handler;

    @BeforeEach
    void setUp() {
        analysisService = mock(CommentService.class);
        commentRepository = mock(CommentRepository.class);
        slackNotifier = mock(SlackNotifier.class);
        handler = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", Collections.singletonList("myname/myrepo"), 3, 10000),
                new CommentAnalyzerProperties(true, true),
                analysisService,
                commentRepository,
                slackNotifier);
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String reviewCommentPayload(String prAuthor, String commentAuthor, String commentAuthorType, String action) {
        return reviewCommentPayload(prAuthor, commentAuthor, commentAuthorType, action, "line");
    }

    private String reviewCommentPayload(String prAuthor, String commentAuthor, String commentAuthorType, String action, String subjectType) {
        return "{\n"
                + "  \"action\": \"" + action + "\",\n"
                + "  \"comment\": {\n"
                + "    \"subject_type\": \"" + subjectType + "\",\n"
                + "    \"id\": 12345,\n"
                + "    \"body\": \"이 부분 X 방식이 더 낫지 않을까요?\",\n"
                + "    \"path\": \"src/Foo.java\",\n"
                + "    \"line\": 42,\n"
                + "    \"side\": \"RIGHT\",\n"
                + "    \"original_line\": 42,\n"
                + "    \"html_url\": \"https://github.com/myname/myrepo/pull/7#discussion_r12345\",\n"
                + "    \"user\": {\"login\": \"" + commentAuthor + "\", \"type\": \"" + commentAuthorType + "\"}\n"
                + "  },\n"
                + "  \"pull_request\": {\n"
                + "    \"number\": 7,\n"
                + "    \"title\": \"A 기능 추가\",\n"
                + "    \"body\": \"A 기능을 B 방식으로 구현\",\n"
                + "    \"user\": {\"login\": \"" + prAuthor + "\", \"type\": \"User\"},\n"
                + "    \"head\": {\"sha\": \"abc123\", \"ref\": \"feat/a\"}\n"
                + "  },\n"
                + "  \"repository\": {\"full_name\": \"myname/myrepo\", \"name\": \"myrepo\", \"owner\": {\"login\": \"myname\", \"type\": \"User\"}}\n"
                + "}";
    }

    private String issueCommentPayload(boolean isPullRequest, String issueAuthor) {
        String prKey = isPullRequest
                ? ", \"pull_request\": {\"url\": \"https://api.github.com/repos/myname/myrepo/pulls/7\"}"
                : "";
        return "{\n"
                + "  \"action\": \"created\",\n"
                + "  \"comment\": {\n"
                + "    \"id\": 999,\n"
                + "    \"body\": \"전반적으로 좋아 보입니다.\",\n"
                + "    \"html_url\": \"https://github.com/myname/myrepo/pull/7#issuecomment-999\",\n"
                + "    \"user\": {\"login\": \"reviewer\", \"type\": \"User\"}\n"
                + "  },\n"
                + "  \"issue\": {\"number\": 7, \"title\": \"A 기능 추가\", \"body\": \"본문\", \"user\": {\"login\": \""
                + issueAuthor + "\", \"type\": \"User\"}" + prKey + "},\n"
                + "  \"repository\": {\"full_name\": \"myname/myrepo\", \"name\": \"myrepo\", \"owner\": {\"login\": \"myname\", \"type\": \"User\"}}\n"
                + "}";
    }

    @Test
    void 리뷰_코멘트_created_이벤트는_분석_대상으로_추출하지_않는다() {
        // 같은 리뷰의 코멘트는 리뷰 제출 이벤트 하나로 묶어 분석하므로 개별 이벤트는 진입점이 아니다
        assertThat(handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "created")))).isEmpty();
    }

    @Test
    void 내_PR에_내가_단_코멘트도_기본설정에서는_추출된다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review",
                bytes(reviewPayload("myname", "myname", "User", "submitted", "commented", "총평")));
        assertThat(ce).isPresent();
    }

    @Test
    void includeOwnComments가_false면_내_코멘트는_제외된다() {
        WebhookEventHandler h = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", Collections.singletonList("myname/myrepo"), 3, 10000),
                new CommentAnalyzerProperties(false, true),
                analysisService,
                commentRepository,
                slackNotifier);
        assertThat(h.extract("pull_request_review",
                bytes(reviewPayload("myname", "myname", "User", "submitted", "commented", "총평")))).isEmpty();
    }

    @Test
    void 봇_코멘트는_제외된다() {
        assertThat(handler.extract("pull_request_review",
                bytes(reviewPayload("myname", "dependabot", "Bot", "submitted", "commented", "총평")))).isEmpty();
        assertThat(handler.extract("pull_request_review",
                bytes(reviewPayload("myname", "some-app[bot]", "User", "submitted", "commented", "총평")))).isEmpty();
    }

    @Test
    void 다른_사람의_PR이면_제외된다() {
        assertThat(handler.extract("issue_comment", bytes(issueCommentPayload(true, "someone-else")))).isEmpty();
    }

    @Test
    void action이_created가_아니면_제외된다() {
        assertThat(handler.extract("issue_comment",
                bytes(issueCommentPayload(true, "myname").replace("\"created\"", "\"edited\"")))).isEmpty();
    }

    @Test
    void PR이_아닌_이슈_코멘트는_제외된다() {
        assertThat(handler.extract("issue_comment", bytes(issueCommentPayload(false, "myname")))).isEmpty();
    }

    @Test
    void 내_PR의_이슈_코멘트는_추출된다() {
        Optional<CommentEvent> ce = handler.extract("issue_comment", bytes(issueCommentPayload(true, "myname")));
        assertThat(ce).isPresent();
        assertThat(ce.get().getEventType()).isEqualTo(CommentEvent.TYPE_ISSUE_COMMENT);
        assertThat(ce.get().getFilePath()).isNull();
        assertThat(ce.get().getHeadSha()).isNull();
        assertThat(ce.get().getPrAuthor()).isEqualTo("myname");
    }

    @Test
    void 관심없는_이벤트는_제외된다() {
        assertThat(handler.extract("push", bytes("{}"))).isEmpty();
        assertThat(handler.extract(null, bytes("{}"))).isEmpty();
    }

    @Test
    void handle는_ping이면_아무것도_트리거하지_않는다() {
        handler.handle("ping", "delivery-1", bytes("{\"zen\":\"...\"}"));
        verify(analysisService, never()).analyzeAsync(any());
    }

    @Test
    void handle는_대상_일반_코멘트면_비동기_분석을_트리거한다() {
        handler.handle("issue_comment", "delivery-2", bytes(issueCommentPayload(true, "myname")));
        ArgumentCaptor<CommentEvent> captor = ArgumentCaptor.forClass(CommentEvent.class);
        verify(analysisService).analyzeAsync(captor.capture());
        assertThat(captor.getValue().getCommentId()).isEqualTo(999L);
        verify(analysisService, never()).analyzeReviewAsync(any());
    }

    @Test
    void handle는_리뷰_코멘트_created_이벤트면_아무것도_트리거하지_않는다() {
        handler.handle("pull_request_review_comment", "delivery-rc",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "created")));
        verify(analysisService, never()).analyzeAsync(any());
        verify(analysisService, never()).analyzeReviewAsync(any());
    }

    @Test
    void subject_type이_없는_issue_comment는_파일_전체_대상_코멘트가_아니다() {
        Optional<CommentEvent> ce = handler.extract("issue_comment",
                bytes(issueCommentPayload(true, "myname")));
        assertThat(ce).isPresent();
        assertThat(ce.get().isFileLevel()).isFalse();
    }

    // --- github.repos 화이트리스트 ---

    @Test
    void github_repos에_없는_저장소의_코멘트는_제외된다() {
        String payload = issueCommentPayload(true, "myname")
                .replace("myname/myrepo\", \"name", "myname/otherrepo\", \"name");
        assertThat(handler.extract("issue_comment", bytes(payload))).isEmpty();
    }

    @Test
    void github_repos_비교는_대소문자를_구분하지_않는다() {
        String payload = issueCommentPayload(true, "myname")
                .replace("myname/myrepo\", \"name", "MyName/MyRepo\", \"name");
        assertThat(handler.extract("issue_comment", bytes(payload))).isPresent();
    }

    @Test
    void github_repos가_비어있으면_전부_차단된다() {
        WebhookEventHandler noRepos = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", Collections.emptyList(), 3, 10000),
                new CommentAnalyzerProperties(true, true),
                analysisService,
                commentRepository,
                slackNotifier);
        assertThat(noRepos.extract("issue_comment", bytes(issueCommentPayload(true, "myname")))).isEmpty();
    }

    // --- pull_request_review (리뷰 총평) ---

    private String reviewPayload(String prAuthor, String reviewer, String reviewerType, String action, String state, String body) {
        return "{\n"
                + "  \"action\": \"" + action + "\",\n"
                + "  \"review\": {\n"
                + "    \"id\": 5114154618,\n"
                + "    \"body\": \"" + body + "\",\n"
                + "    \"state\": \"" + state + "\",\n"
                + "    \"html_url\": \"https://github.com/myname/myrepo/pull/7#pullrequestreview-5114154618\",\n"
                + "    \"user\": {\"login\": \"" + reviewer + "\", \"type\": \"" + reviewerType + "\"}\n"
                + "  },\n"
                + "  \"pull_request\": {\n"
                + "    \"number\": 7,\n"
                + "    \"title\": \"A 기능 추가\",\n"
                + "    \"body\": \"본문\",\n"
                + "    \"user\": {\"login\": \"" + prAuthor + "\", \"type\": \"User\"},\n"
                + "    \"head\": {\"sha\": \"abc123\", \"ref\": \"feat/a\"}\n"
                + "  },\n"
                + "  \"repository\": {\"full_name\": \"myname/myrepo\", \"name\": \"myrepo\", \"owner\": {\"login\": \"myname\", \"type\": \"User\"}}\n"
                + "}";
    }

    @Test
    void 총평이_있는_리뷰_제출은_추출된다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review",
                bytes(reviewPayload("myname", "reviewer", "User", "submitted", "changes_requested", "전반적으로 트랜잭션 경계가 어색합니다")));
        assertThat(ce).isPresent();
        CommentEvent e = ce.get();
        assertThat(e.getEventType()).isEqualTo(CommentEvent.TYPE_REVIEW_BODY);
        assertThat(e.isReviewBody()).isTrue();
        assertThat(e.isReviewComment()).isFalse();
        assertThat(e.getCommentId()).isEqualTo(5114154618L);
        assertThat(e.getCommentBody()).isEqualTo("전반적으로 트랜잭션 경계가 어색합니다");
        assertThat(e.getReviewState()).isEqualTo("changes_requested");
        assertThat(e.getCommentAuthor()).isEqualTo("reviewer");
        assertThat(e.getHeadSha()).isEqualTo("abc123");
        assertThat(e.getPrNumber()).isEqualTo(7);
        // 스레드에서 PR 작성자의 말을 구분하는 기준
        assertThat(e.getPrAuthor()).isEqualTo("myname");
        // 총평은 파일/라인 정보가 없다
        assertThat(e.getFilePath()).isNull();
        assertThat(e.getLine()).isNull();
    }

    @Test
    void 본문이_빈_리뷰_제출도_추출된다() {
        // 인라인 코멘트만 담긴 리뷰(단일 코멘트, 답글 포함)도 분석 대상 - 분석할 코멘트가 있는지는 서비스가 조회해 판단한다
        Optional<CommentEvent> ce = handler.extract("pull_request_review",
                bytes(reviewPayload("myname", "reviewer", "User", "submitted", "commented", "")));
        assertThat(ce).isPresent();
        assertThat(ce.get().isReviewBody()).isTrue();
        assertThat(ce.get().getCommentId()).isEqualTo(5114154618L);
    }

    @Test
    void submitted가_아닌_리뷰_액션은_제외된다() {
        assertThat(handler.extract("pull_request_review",
                bytes(reviewPayload("myname", "reviewer", "User", "edited", "commented", "수정됨")))).isEmpty();
        assertThat(handler.extract("pull_request_review",
                bytes(reviewPayload("myname", "reviewer", "User", "dismissed", "dismissed", "기각됨")))).isEmpty();
    }

    @Test
    void 타인_PR이거나_봇이_남긴_총평은_제외된다() {
        assertThat(handler.extract("pull_request_review",
                bytes(reviewPayload("someone-else", "reviewer", "User", "submitted", "commented", "총평")))).isEmpty();
        assertThat(handler.extract("pull_request_review",
                bytes(reviewPayload("myname", "sonarcloud", "Bot", "submitted", "commented", "총평")))).isEmpty();
    }

    @Test
    void handle는_총평_리뷰면_분석을_트리거한다() {
        handler.handle("pull_request_review", "delivery-rb",
                bytes(reviewPayload("myname", "reviewer", "User", "submitted", "commented", "총평입니다")));
        ArgumentCaptor<CommentEvent> captor = ArgumentCaptor.forClass(CommentEvent.class);
        verify(analysisService).analyzeReviewAsync(captor.capture());
        assertThat(captor.getValue().isReviewBody()).isTrue();
        verify(analysisService, never()).analyzeAsync(any());
    }

    @Test
    void handle는_본문이_빈_리뷰도_리뷰_단위_분석을_트리거한다() {
        handler.handle("pull_request_review", "delivery-empty",
                bytes(reviewPayload("myname", "reviewer", "User", "submitted", "commented", "")));
        verify(analysisService).analyzeReviewAsync(any());
    }

    @Test
    void 코멘트_분석이_비활성이면_분석을_트리거하지_않는다() {
        WebhookEventHandler off = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", Collections.singletonList("myname/myrepo"), 3, 10000),
                // 3번째 인자가 enabled(kill-switch)
                new CommentAnalyzerProperties(true, false),
                analysisService,
                commentRepository,
                slackNotifier);

        off.handle("pull_request_review", "delivery-off",
                bytes(reviewPayload("myname", "reviewer", "User", "submitted", "commented", "총평")));

        verify(analysisService, never()).analyzeReviewAsync(any());
    }

    @Test
    void handle는_필터_탈락이면_아무것도_트리거하지_않는다() {
        handler.handle("pull_request_review", "delivery-3",
                bytes(reviewPayload("someone-else", "reviewer", "User", "submitted", "commented", "총평")));
        verify(analysisService, never()).analyzeReviewAsync(any());
    }

    @Test
    void handle는_분석했던_코멘트가_삭제되면_Slack에_알리고_분석은_트리거하지_않는다() {
        when(commentRepository.existsById(12345L)).thenReturn(true);

        handler.handle("pull_request_review_comment", "delivery-del",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "deleted")));

        verify(slackNotifier).sendCommentDeleted("myname/myrepo", 7, 12345L,
                "https://github.com/myname/myrepo/pull/7#discussion_r12345");
        verify(analysisService, never()).analyzeAsync(any());
    }

    @Test
    void handle는_분석한_적_없는_코멘트의_삭제는_알리지_않는다() {
        when(commentRepository.existsById(12345L)).thenReturn(false);

        handler.handle("pull_request_review_comment", "delivery-del2",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "deleted")));

        verify(slackNotifier, never()).sendCommentDeleted(any(), anyInt(), anyLong(), any());
    }

    @Test
    void handle는_대상_레포가_아닌_코멘트_삭제는_알리지_않는다() {
        String payload = reviewCommentPayload("myname", "reviewer", "User", "deleted")
                .replace("myname/myrepo", "other/repo");

        handler.handle("pull_request_review_comment", "delivery-del3", bytes(payload));

        verify(commentRepository, never()).existsById(anyLong());
        verify(slackNotifier, never()).sendCommentDeleted(any(), anyInt(), anyLong(), any());
    }

    @Test
    void handle는_일반_코멘트_삭제도_issue의_PR번호로_알린다() {
        when(commentRepository.existsById(999L)).thenReturn(true);
        String payload = issueCommentPayload(true, "myname").replace("\"created\"", "\"deleted\"");

        handler.handle("issue_comment", "delivery-del4", bytes(payload));

        verify(slackNotifier).sendCommentDeleted("myname/myrepo", 7, 999L,
                "https://github.com/myname/myrepo/pull/7#issuecomment-999");
    }
}
