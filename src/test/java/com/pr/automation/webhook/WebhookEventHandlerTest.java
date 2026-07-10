package com.pr.automation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.CommentAnalysisService;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.config.GithubProperties;
import com.pr.automation.config.PrAnalyzerProperties;
import com.pr.automation.config.PrReviewProperties;
import com.pr.automation.review.PrReviewService;
import com.pr.automation.review.dto.PrReviewEvent;
import com.pr.automation.webhook.recovery.DeliveryStore;
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
    private DeliveryStore deliveryStore;
    private PrReviewService prReviewService;
    private WebhookEventHandler handler;

    @BeforeEach
    void setUp() {
        analysisService = mock(CommentAnalysisService.class);
        deliveryStore = mock(DeliveryStore.class);
        prReviewService = mock(PrReviewService.class);
        handler = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", null, null),
                new PrAnalyzerProperties(true, "./state.json", 8, 6, 25000),
                analysisService,
                deliveryStore,
                prReviewService,
                new PrReviewProperties(true, "./review-state.json", 50, 6000, false));
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
    void 내_PR에_타인이_단_리뷰코멘트는_추출된다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review_comment", "d-1",
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
        assertThat(e.getSide()).isEqualTo("RIGHT");
        assertThat(e.getStartLine()).isNull();
        assertThat(e.getOriginalLine()).isEqualTo(42);
        assertThat(e.getHeadSha()).isEqualTo("abc123");
        assertThat(e.getDeliveryId()).isEqualTo("d-1");
    }

    // 코멘트 앵커 필드만 바꿔 끼울 수 있는 페이로드 (anchorFieldsJson 예: "\"line\": 42, \"side\": \"LEFT\",")
    private String reviewCommentPayloadWithAnchor(String anchorFieldsJson) {
        return "{\n"
                + "  \"action\": \"created\",\n"
                + "  \"comment\": {\n"
                + "    \"id\": 12345,\n"
                + "    \"body\": \"코멘트\",\n"
                + "    \"path\": \"src/Foo.java\",\n"
                + "    \"diff_hunk\": \"@@ -1,3 +1,4 @@\\n+line\",\n"
                + "    " + anchorFieldsJson + "\n"
                + "    \"html_url\": \"https://github.com/myname/myrepo/pull/7#discussion_r12345\",\n"
                + "    \"user\": {\"login\": \"reviewer\", \"type\": \"User\"}\n"
                + "  },\n"
                + "  \"pull_request\": {\n"
                + "    \"number\": 7,\n"
                + "    \"title\": \"A 기능 추가\",\n"
                + "    \"body\": \"본문\",\n"
                + "    \"user\": {\"login\": \"myname\", \"type\": \"User\"},\n"
                + "    \"head\": {\"sha\": \"abc123\", \"ref\": \"feat/a\"}\n"
                + "  },\n"
                + "  \"repository\": {\"full_name\": \"myname/myrepo\", \"name\": \"myrepo\", \"owner\": {\"login\": \"myname\", \"type\": \"User\"}}\n"
                + "}";
    }

    @Test
    void 멀티라인_LEFT_코멘트의_앵커정보가_보존된다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review_comment", "d-a1",
                bytes(reviewCommentPayloadWithAnchor("\"line\": 42, \"side\": \"LEFT\", \"start_line\": 38,")));
        assertThat(ce).isPresent();
        CommentEvent e = ce.get();
        assertThat(e.getLine()).isEqualTo(42);
        assertThat(e.getSide()).isEqualTo("LEFT");
        assertThat(e.getStartLine()).isEqualTo(38);
    }

    @Test
    void outdated_코멘트는_line이_null이고_originalLine만_보존된다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review_comment", "d-a2",
                bytes(reviewCommentPayloadWithAnchor("\"line\": null, \"original_line\": 99,")));
        assertThat(ce).isPresent();
        CommentEvent e = ce.get();
        // 과거 커밋 기준 라인을 head 기준인 것처럼 합치지 않는다 (폴백 제거)
        assertThat(e.getLine()).isNull();
        assertThat(e.getOriginalLine()).isEqualTo(99);
    }

    @Test
    void 내_PR에_내가_단_코멘트도_기본설정에서는_추출된다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review_comment", "d-2",
                bytes(reviewCommentPayload("myname", "myname", "User", "created")));
        assertThat(ce).isPresent();
    }

    @Test
    void includeOwnComments가_false면_내_코멘트는_제외된다() {
        WebhookEventHandler h = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", null, null),
                new PrAnalyzerProperties(false, "./state.json", 8, 6, 25000),
                analysisService,
                deliveryStore,
                prReviewService,
                new PrReviewProperties(true, "./review-state.json", 50, 6000, false));
        assertThat(h.extract("pull_request_review_comment", "d-3",
                bytes(reviewCommentPayload("myname", "myname", "User", "created")))).isEmpty();
    }

    @Test
    void 봇_코멘트는_제외된다() {
        assertThat(handler.extract("pull_request_review_comment", "d",
                bytes(reviewCommentPayload("myname", "dependabot", "Bot", "created")))).isEmpty();
        assertThat(handler.extract("pull_request_review_comment", "d",
                bytes(reviewCommentPayload("myname", "some-app[bot]", "User", "created")))).isEmpty();
    }

    @Test
    void 다른_사람의_PR이면_제외된다() {
        assertThat(handler.extract("pull_request_review_comment", "d",
                bytes(reviewCommentPayload("someone-else", "reviewer", "User", "created")))).isEmpty();
    }

    @Test
    void action이_created가_아니면_제외된다() {
        assertThat(handler.extract("pull_request_review_comment", "d",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "edited")))).isEmpty();
    }

    @Test
    void PR이_아닌_이슈_코멘트는_제외된다() {
        assertThat(handler.extract("issue_comment", "d", bytes(issueCommentPayload(false, "myname")))).isEmpty();
    }

    @Test
    void 내_PR의_이슈_코멘트는_추출된다() {
        Optional<CommentEvent> ce = handler.extract("issue_comment", "d", bytes(issueCommentPayload(true, "myname")));
        assertThat(ce).isPresent();
        assertThat(ce.get().getEventType()).isEqualTo(CommentEvent.TYPE_ISSUE_COMMENT);
        assertThat(ce.get().getFilePath()).isNull();
        assertThat(ce.get().getHeadSha()).isNull();
    }

    @Test
    void 관심없는_이벤트는_제외된다() {
        assertThat(handler.extract("push", "d", bytes("{}"))).isEmpty();
        assertThat(handler.extract(null, "d", bytes("{}"))).isEmpty();
    }

    @Test
    void handle는_ping이면_분석을_트리거하지_않고_delivery만_ack한다() {
        handler.handle("ping", "delivery-1", bytes("{\"zen\":\"...\"}"));
        verify(analysisService, never()).analyzeAsync(any());
        verify(deliveryStore).markReceived("delivery-1");
    }

    @Test
    void handle는_대상_코멘트면_비동기_분석을_트리거하고_delivery는_ack하지_않는다() {
        handler.handle("pull_request_review_comment", "delivery-2",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "created")));
        ArgumentCaptor<CommentEvent> captor = ArgumentCaptor.forClass(CommentEvent.class);
        verify(analysisService).analyzeAsync(captor.capture());
        assertThat(captor.getValue().getCommentId()).isEqualTo(12345L);
        assertThat(captor.getValue().getDeliveryId()).isEqualTo("delivery-2");
        // 분석 분기에서는 markReceived를 직접 호출하지 않음 — CommentAnalysisService가 성공 후 호출
        verify(deliveryStore, never()).markReceived(any());
    }

    @Test
    void handle는_필터_탈락이면_delivery를_ack한다() {
        handler.handle("pull_request_review_comment", "delivery-3",
                bytes(reviewCommentPayload("someone-else", "reviewer", "User", "created")));
        verify(analysisService, never()).analyzeAsync(any());
        verify(deliveryStore).markReceived("delivery-3");
    }

    // --- PR 생성(opened) 자동 리뷰 경로 ---

    private String pullRequestPayload(String prAuthor, String prAuthorType, String action) {
        return "{\n"
                + "  \"action\": \"" + action + "\",\n"
                + "  \"pull_request\": {\n"
                + "    \"number\": 7,\n"
                + "    \"title\": \"A 기능 추가\",\n"
                + "    \"body\": \"A 기능을 B 방식으로 구현\",\n"
                + "    \"html_url\": \"https://github.com/myname/myrepo/pull/7\",\n"
                + "    \"user\": {\"login\": \"" + prAuthor + "\", \"type\": \"" + prAuthorType + "\"},\n"
                + "    \"head\": {\"sha\": \"abc123\", \"ref\": \"feat/a\"}\n"
                + "  },\n"
                + "  \"repository\": {\"full_name\": \"myname/myrepo\", \"name\": \"myrepo\", \"owner\": {\"login\": \"myname\", \"type\": \"User\"}}\n"
                + "}";
    }

    @Test
    void 내가_연_PR_opened는_리뷰_대상으로_추출된다() {
        Optional<PrReviewEvent> pre = handler.extractPullRequest("d-pr",
                bytes(pullRequestPayload("myname", "User", "opened")));
        assertThat(pre).isPresent();
        PrReviewEvent e = pre.get();
        assertThat(e.getRepoFullName()).isEqualTo("myname/myrepo");
        assertThat(e.getPrNumber()).isEqualTo(7);
        assertThat(e.getHeadSha()).isEqualTo("abc123");
        assertThat(e.getPrAuthor()).isEqualTo("myname");
        assertThat(e.getPrHtmlUrl()).isEqualTo("https://github.com/myname/myrepo/pull/7");
        assertThat(e.getDeliveryId()).isEqualTo("d-pr");
    }

    @Test
    void opened가_아닌_PR_액션은_제외된다() {
        assertThat(handler.extractPullRequest("d", bytes(pullRequestPayload("myname", "User", "synchronize")))).isEmpty();
        assertThat(handler.extractPullRequest("d", bytes(pullRequestPayload("myname", "User", "closed")))).isEmpty();
    }

    @Test
    void 다른_사람이_연_PR이나_봇_PR은_제외된다() {
        assertThat(handler.extractPullRequest("d", bytes(pullRequestPayload("someone-else", "User", "opened")))).isEmpty();
        assertThat(handler.extractPullRequest("d", bytes(pullRequestPayload("dependabot", "Bot", "opened")))).isEmpty();
    }

    @Test
    void pr_review가_비활성이면_PR은_추출되지_않는다() {
        WebhookEventHandler disabled = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", null, null),
                new PrAnalyzerProperties(true, "./state.json", 8, 6, 25000),
                analysisService,
                deliveryStore,
                prReviewService,
                new PrReviewProperties(false, "./review-state.json", 50, 6000, false));
        assertThat(disabled.extractPullRequest("d", bytes(pullRequestPayload("myname", "User", "opened")))).isEmpty();
    }

    @Test
    void handle는_내_PR_opened면_리뷰를_트리거하고_delivery는_ack하지_않는다() {
        handler.handle("pull_request", "delivery-pr",
                bytes(pullRequestPayload("myname", "User", "opened")));
        ArgumentCaptor<PrReviewEvent> captor = ArgumentCaptor.forClass(PrReviewEvent.class);
        verify(prReviewService).reviewAsync(captor.capture());
        assertThat(captor.getValue().getPrNumber()).isEqualTo(7);
        assertThat(captor.getValue().getDeliveryId()).isEqualTo("delivery-pr");
        verify(deliveryStore, never()).markReceived(any());
    }

    @Test
    void handle는_PR_리뷰_대상이_아니면_delivery를_ack한다() {
        handler.handle("pull_request", "delivery-pr2",
                bytes(pullRequestPayload("someone-else", "User", "opened")));
        verify(prReviewService, never()).reviewAsync(any());
        verify(deliveryStore).markReceived("delivery-pr2");
    }
}
