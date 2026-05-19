package com.pr.automation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.CommentAnalysisService;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.config.GithubProperties;
import com.pr.automation.config.PrAnalyzerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WebhookEventHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommentAnalysisService analysisService;
    private WebhookEventHandler handler;

    @BeforeEach
    void setUp() {
        analysisService = mock(CommentAnalysisService.class);
        handler = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret"),
                new PrAnalyzerProperties(true, "./state.json", 40, 8, 6, 25000),
                analysisService);
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String reviewCommentPayload(String prAuthor, String commentAuthor, String commentAuthorType, String action) {
        return "{\n"
                + "  \"action\": \"" + action + "\",\n"
                + "  \"comment\": {\n"
                + "    \"id\": 12345,\n"
                + "    \"body\": \"이 부분 X 방식이 더 낫지 않을까요?\",\n"
                + "    \"path\": \"src/Foo.java\",\n"
                + "    \"diff_hunk\": \"@@ -1,3 +1,4 @@\\n+line\",\n"
                + "    \"line\": 42,\n"
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
    void 내_PR에_타인이_단_리뷰코멘트는_추출된다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "created")));
        assertThat(ce).isPresent();
        CommentEvent e = ce.get();
        assertThat(e.getEventType()).isEqualTo(CommentEvent.TYPE_REVIEW_COMMENT);
        assertThat(e.getRepoFullName()).isEqualTo("myname/myrepo");
        assertThat(e.getPrNumber()).isEqualTo(7);
        assertThat(e.getCommentId()).isEqualTo(12345L);
        assertThat(e.getCommentAuthor()).isEqualTo("reviewer");
        assertThat(e.getFilePath()).isEqualTo("src/Foo.java");
        assertThat(e.getLine()).isEqualTo(42);
        assertThat(e.getHeadSha()).isEqualTo("abc123");
    }

    @Test
    void 내_PR에_내가_단_코멘트도_기본설정에서는_추출된다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("myname", "myname", "User", "created")));
        assertThat(ce).isPresent();
    }

    @Test
    void includeOwnComments가_false면_내_코멘트는_제외된다() {
        WebhookEventHandler h = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret"),
                new PrAnalyzerProperties(false, "./state.json", 40, 8, 6, 25000),
                analysisService);
        assertThat(h.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("myname", "myname", "User", "created")))).isEmpty();
    }

    @Test
    void 봇_코멘트는_제외된다() {
        assertThat(handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("myname", "dependabot", "Bot", "created")))).isEmpty();
        assertThat(handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("myname", "some-app[bot]", "User", "created")))).isEmpty();
    }

    @Test
    void 다른_사람의_PR이면_제외된다() {
        assertThat(handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("someone-else", "reviewer", "User", "created")))).isEmpty();
    }

    @Test
    void action이_created가_아니면_제외된다() {
        assertThat(handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "edited")))).isEmpty();
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
    }

    @Test
    void 관심없는_이벤트는_제외된다() {
        assertThat(handler.extract("push", bytes("{}"))).isEmpty();
        assertThat(handler.extract(null, bytes("{}"))).isEmpty();
    }

    @Test
    void handle는_ping이면_분석을_트리거하지_않는다() {
        handler.handle("ping", "delivery-1", bytes("{\"zen\":\"...\"}"));
        verify(analysisService, never()).analyzeAsync(any());
    }

    @Test
    void handle는_대상_코멘트면_비동기_분석을_트리거한다() {
        handler.handle("pull_request_review_comment", "delivery-2",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "created")));
        ArgumentCaptor<CommentEvent> captor = ArgumentCaptor.forClass(CommentEvent.class);
        verify(analysisService).analyzeAsync(captor.capture());
        assertThat(captor.getValue().getCommentId()).isEqualTo(12345L);
    }
}
