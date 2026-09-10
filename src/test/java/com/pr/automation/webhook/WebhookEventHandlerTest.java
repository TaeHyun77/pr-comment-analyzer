package com.pr.automation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.CommentAnalysisService;
import com.pr.automation.analysis.comment.CommentStore;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.config.properties.GithubProperties;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
import com.pr.automation.config.properties.PrReviewProperties;
import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import com.pr.automation.github.GithubClient;
import com.pr.automation.analysis.pr.PrReviewService;
import com.pr.automation.slack.SlackNotifier;
import com.pr.automation.analysis.pr.dto.PrReviewEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookEventHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommentAnalysisService analysisService;
    private PrReviewService prReviewService;
    private GithubClient githubClient;
    private CommentStore commentStore;
    private SlackNotifier slackNotifier;
    private WebhookEventHandler handler;

    @BeforeEach
    void setUp() {
        analysisService = mock(CommentAnalysisService.class);
        prReviewService = mock(PrReviewService.class);
        githubClient = mock(GithubClient.class);
        commentStore = mock(CommentStore.class);
        slackNotifier = mock(SlackNotifier.class);
        handler = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", Collections.singletonList("myname/myrepo"), 3, 10000),
                new CommentAnalyzerProperties(true, 25000, true, 25, 3, 20),
                analysisService,
                commentStore,
                slackNotifier,
                prReviewService,
                new PrReviewProperties(true, 50, 6000, false),
                githubClient);
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
        assertThat(e.getSide()).isEqualTo("RIGHT");
        assertThat(e.getStartLine()).isNull();
        assertThat(e.getOriginalLine()).isEqualTo(42);
        assertThat(e.getHeadSha()).isEqualTo("abc123");
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
        Optional<CommentEvent> ce = handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayloadWithAnchor("\"line\": 42, \"side\": \"LEFT\", \"start_line\": 38,")));
        assertThat(ce).isPresent();
        CommentEvent e = ce.get();
        assertThat(e.getLine()).isEqualTo(42);
        assertThat(e.getSide()).isEqualTo("LEFT");
        assertThat(e.getStartLine()).isEqualTo(38);
    }

    @Test
    void outdated_코멘트는_line이_null이고_originalLine만_보존된다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayloadWithAnchor("\"line\": null, \"original_line\": 99,")));
        assertThat(ce).isPresent();
        CommentEvent e = ce.get();
        // 과거 커밋 기준 라인을 head 기준인 것처럼 합치지 않는다 (폴백 제거)
        assertThat(e.getLine()).isNull();
        assertThat(e.getOriginalLine()).isEqualTo(99);
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
                new GithubProperties("token", "myname", "secret", Collections.singletonList("myname/myrepo"), 3, 10000),
                new CommentAnalyzerProperties(false, 25000, true, 25, 3, 20),
                analysisService,
                commentStore,
                slackNotifier,
                prReviewService,
                new PrReviewProperties(true, 50, 6000, false),
                githubClient);
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
    void handle는_ping이면_아무것도_트리거하지_않는다() {
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

    @Test
    void subject_type이_file이면_파일_전체_대상_코멘트로_판별된다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "created", "file")));
        assertThat(ce).isPresent();
        assertThat(ce.get().isFileLevel()).isTrue();
    }

    @Test
    void subject_type이_line이면_파일_전체_대상_코멘트가_아니다() {
        Optional<CommentEvent> ce = handler.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "created")));
        assertThat(ce).isPresent();
        assertThat(ce.get().isFileLevel()).isFalse();
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
        String payload = reviewCommentPayload("myname", "reviewer", "User", "created")
                .replace("myname/myrepo\", \"name", "myname/otherrepo\", \"name");
        assertThat(handler.extract("pull_request_review_comment", bytes(payload))).isEmpty();
    }

    @Test
    void github_repos_비교는_대소문자를_구분하지_않는다() {
        String payload = reviewCommentPayload("myname", "reviewer", "User", "created")
                .replace("myname/myrepo\", \"name", "MyName/MyRepo\", \"name");
        assertThat(handler.extract("pull_request_review_comment", bytes(payload))).isPresent();
    }

    @Test
    void github_repos가_비어있으면_전부_차단된다() {
        WebhookEventHandler noRepos = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", Collections.emptyList(), 3, 10000),
                new CommentAnalyzerProperties(true, 25000, true, 25, 3, 20),
                analysisService,
                commentStore,
                slackNotifier,
                prReviewService,
                new PrReviewProperties(true, 50, 6000, false),
                githubClient);
        assertThat(noRepos.extract("pull_request_review_comment",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "created")))).isEmpty();
        assertThat(noRepos.extractPullRequest(
                bytes(pullRequestPayload("myname", "User", "opened")))).isEmpty();
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
        // 총평은 파일/라인 정보가 없다
        assertThat(e.getFilePath()).isNull();
        assertThat(e.getLine()).isNull();
    }

    @Test
    void 본문이_빈_리뷰는_제외된다() {
        // Approve만 누른 경우와 단일 인라인 코멘트가 만든 리뷰가 모두 여기 해당
        assertThat(handler.extract("pull_request_review",
                bytes(reviewPayload("myname", "reviewer", "User", "submitted", "approved", "")))).isEmpty();
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
        verify(analysisService).analyzeAsync(captor.capture());
        assertThat(captor.getValue().isReviewBody()).isTrue();
    }

    @Test
    void handle는_본문이_빈_리뷰면_아무것도_트리거하지_않는다() {
        handler.handle("pull_request_review", "delivery-empty",
                bytes(reviewPayload("myname", "reviewer", "User", "submitted", "approved", "")));
        verify(analysisService, never()).analyzeAsync(any());
    }

    // --- commit_comment (커밋에 달린 코멘트) ---

    private String commitCommentPayload(String commentAuthor, String commentAuthorType) {
        return "{\n"
                + "  \"action\": \"created\",\n"
                + "  \"comment\": {\n"
                + "    \"id\": 777,\n"
                + "    \"body\": \"이 커밋의 계산식이 이상합니다\",\n"
                + "    \"path\": \"src/Foo.java\",\n"
                + "    \"line\": 11,\n"
                + "    \"commit_id\": \"deadbeef\",\n"
                + "    \"html_url\": \"https://github.com/myname/myrepo/commit/deadbeef#commitcomment-777\",\n"
                + "    \"user\": {\"login\": \"" + commentAuthor + "\", \"type\": \"" + commentAuthorType + "\"}\n"
                + "  },\n"
                + "  \"repository\": {\"full_name\": \"myname/myrepo\", \"name\": \"myrepo\", \"owner\": {\"login\": \"myname\", \"type\": \"User\"}}\n"
                + "}";
    }

    private void givenOpenPull(String prAuthor) {
        when(githubClient.fetchOpenPullForCommit("myname/myrepo", "deadbeef"))
                .thenReturn(Optional.of(GithubClient.PullRef.builder()
                        .number(7).title("A 기능 추가").body("본문").author(prAuthor).build()));
    }

    @Test
    void 커밋_코멘트는_소속_열린_PR을_조회해_추출된다() {
        givenOpenPull("myname");
        Optional<CommentEvent> ce = handler.extract("commit_comment", bytes(commitCommentPayload("reviewer", "User")));
        assertThat(ce).isPresent();
        CommentEvent e = ce.get();
        assertThat(e.getEventType()).isEqualTo(CommentEvent.TYPE_COMMIT_COMMENT);
        assertThat(e.isCommitComment()).isTrue();
        assertThat(e.getPrNumber()).isEqualTo(7);
        assertThat(e.getPrTitle()).isEqualTo("A 기능 추가");
        assertThat(e.getCommentId()).isEqualTo(777L);
        assertThat(e.getFilePath()).isEqualTo("src/Foo.java");
        assertThat(e.getLine()).isEqualTo(11);
        // 파일 조회 기준은 PR head가 아니라 코멘트가 달린 커밋
        assertThat(e.getHeadSha()).isEqualTo("deadbeef");
    }

    @Test
    void 열린_PR이_없는_커밋_코멘트는_제외된다() {
        when(githubClient.fetchOpenPullForCommit("myname/myrepo", "deadbeef")).thenReturn(Optional.empty());
        assertThat(handler.extract("commit_comment", bytes(commitCommentPayload("reviewer", "User")))).isEmpty();
    }

    @Test
    void 커밋_코멘트의_PR_조회_실패는_예외로_전파된다() {
        when(githubClient.fetchOpenPullForCommit("myname/myrepo", "deadbeef"))
                .thenThrow(new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR, "조회 실패"));
        assertThatThrownBy(() -> handler.extract("commit_comment", bytes(commitCommentPayload("reviewer", "User"))))
                .isInstanceOf(AutomationException.class);
    }

    @Test
    void 타인_PR이거나_봇이_단_커밋_코멘트는_제외된다() {
        givenOpenPull("someone-else");
        assertThat(handler.extract("commit_comment", bytes(commitCommentPayload("reviewer", "User")))).isEmpty();

        givenOpenPull("myname");
        assertThat(handler.extract("commit_comment", bytes(commitCommentPayload("dependabot", "Bot")))).isEmpty();
    }

    @Test
    void github_repos에_없는_저장소의_커밋_코멘트는_PR_조회도_하지_않는다() {
        String payload = commitCommentPayload("reviewer", "User")
                .replace("myname/myrepo\", \"name", "myname/otherrepo\", \"name");
        assertThat(handler.extract("commit_comment", bytes(payload))).isEmpty();
        verify(githubClient, never()).fetchOpenPullForCommit(any(), any());
    }

    @Test
    void handle는_커밋_코멘트면_분석을_트리거한다() {
        givenOpenPull("myname");
        handler.handle("commit_comment", "delivery-cc", bytes(commitCommentPayload("reviewer", "User")));
        ArgumentCaptor<CommentEvent> captor = ArgumentCaptor.forClass(CommentEvent.class);
        verify(analysisService).analyzeAsync(captor.capture());
        assertThat(captor.getValue().isCommitComment()).isTrue();
    }

    @Test
    void 코멘트_분석이_비활성이면_커밋_코멘트의_PR_조회도_하지_않는다() {
        WebhookEventHandler off = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", Collections.singletonList("myname/myrepo"), 3, 10000),
                // 6번째 인자가 enabled(kill-switch)
                new CommentAnalyzerProperties(true, 25000, false, 25, 3, 20),
                analysisService,
                commentStore,
                slackNotifier,
                prReviewService,
                new PrReviewProperties(true, 50, 6000, false),
                githubClient);

        off.handle("commit_comment", "delivery-off", bytes(commitCommentPayload("reviewer", "User")));

        verify(githubClient, never()).fetchOpenPullForCommit(any(), any());
        verify(analysisService, never()).analyzeAsync(any());
    }

    @Test
    void handle는_필터_탈락이면_아무것도_트리거하지_않는다() {
        handler.handle("pull_request_review_comment", "delivery-3",
                bytes(reviewCommentPayload("someone-else", "reviewer", "User", "created")));
        verify(analysisService, never()).analyzeAsync(any());
    }

    // --- PR 생성(opened) 자동 리뷰 경로 ---

    private String pullRequestPayload(String prAuthor, String prAuthorType, String action) {
        return pullRequestPayload(prAuthor, prAuthorType, action, false);
    }

    private String pullRequestPayload(String prAuthor, String prAuthorType, String action, boolean draft) {
        return "{\n"
                + "  \"action\": \"" + action + "\",\n"
                + "  \"pull_request\": {\n"
                + "    \"draft\": " + draft + ",\n"
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
        Optional<PrReviewEvent> pre = handler.extractPullRequest(
                bytes(pullRequestPayload("myname", "User", "opened")));
        assertThat(pre).isPresent();
        PrReviewEvent e = pre.get();
        assertThat(e.getRepoFullName()).isEqualTo("myname/myrepo");
        assertThat(e.getPrNumber()).isEqualTo(7);
        assertThat(e.getHeadSha()).isEqualTo("abc123");
        assertThat(e.getPrAuthor()).isEqualTo("myname");
        assertThat(e.getPrHtmlUrl()).isEqualTo("https://github.com/myname/myrepo/pull/7");
    }

    @Test
    void opened가_아닌_PR_액션은_제외된다() {
        assertThat(handler.extractPullRequest(bytes(pullRequestPayload("myname", "User", "synchronize")))).isEmpty();
        assertThat(handler.extractPullRequest(bytes(pullRequestPayload("myname", "User", "closed")))).isEmpty();
    }

    @Test
    void draft로_열린_PR은_리뷰_대상에서_제외된다() {
        assertThat(handler.extractPullRequest(bytes(pullRequestPayload("myname", "User", "opened", true)))).isEmpty();
    }

    @Test
    void draft를_ready로_전환하면_리뷰_대상으로_추출된다() {
        // Ready 전환 시점 페이로드는 draft가 false로 내려오지만, 액션만으로 판정되는지 확인하기 위해 true로 둔다
        Optional<PrReviewEvent> pre = handler.extractPullRequest(
                bytes(pullRequestPayload("myname", "User", "ready_for_review", true)));
        assertThat(pre).isPresent();
        assertThat(pre.get().getPrNumber()).isEqualTo(7);
    }

    @Test
    void 다른_사람이_연_PR이나_봇_PR은_제외된다() {
        assertThat(handler.extractPullRequest(bytes(pullRequestPayload("someone-else", "User", "opened")))).isEmpty();
        assertThat(handler.extractPullRequest(bytes(pullRequestPayload("dependabot", "Bot", "opened")))).isEmpty();
    }

    @Test
    void pr_review가_비활성이면_PR은_추출되지_않는다() {
        WebhookEventHandler disabled = new WebhookEventHandler(
                objectMapper,
                new GithubProperties("token", "myname", "secret", Collections.singletonList("myname/myrepo"), 3, 10000),
                new CommentAnalyzerProperties(true, 25000, true, 25, 3, 20),
                analysisService,
                commentStore,
                slackNotifier,
                prReviewService,
                new PrReviewProperties(false, 50, 6000, false),
                githubClient);
        assertThat(disabled.extractPullRequest(bytes(pullRequestPayload("myname", "User", "opened")))).isEmpty();
    }

    @Test
    void handle는_내_PR_opened면_리뷰를_트리거한다() {
        handler.handle("pull_request", "delivery-pr",
                bytes(pullRequestPayload("myname", "User", "opened")));
        ArgumentCaptor<PrReviewEvent> captor = ArgumentCaptor.forClass(PrReviewEvent.class);
        verify(prReviewService).reviewAsync(captor.capture());
        assertThat(captor.getValue().getPrNumber()).isEqualTo(7);
    }

    @Test
    void handle는_PR_리뷰_대상이_아니면_아무것도_트리거하지_않는다() {
        handler.handle("pull_request", "delivery-pr2",
                bytes(pullRequestPayload("someone-else", "User", "opened")));
        verify(prReviewService, never()).reviewAsync(any());
    }

    @Test
    void handle는_분석했던_코멘트가_삭제되면_Slack에_알리고_분석은_트리거하지_않는다() {
        when(commentStore.isTracked(12345L)).thenReturn(true);

        handler.handle("pull_request_review_comment", "delivery-del",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "deleted")));

        verify(slackNotifier).sendCommentDeleted("myname/myrepo", 7, 12345L,
                "https://github.com/myname/myrepo/pull/7#discussion_r12345");
        verify(analysisService, never()).analyzeAsync(any());
    }

    @Test
    void handle는_분석한_적_없는_코멘트의_삭제는_알리지_않는다() {
        when(commentStore.isTracked(12345L)).thenReturn(false);

        handler.handle("pull_request_review_comment", "delivery-del2",
                bytes(reviewCommentPayload("myname", "reviewer", "User", "deleted")));

        verify(slackNotifier, never()).sendCommentDeleted(any(), anyInt(), anyLong(), any());
    }

    @Test
    void handle는_대상_레포가_아닌_코멘트_삭제는_알리지_않는다() {
        String payload = reviewCommentPayload("myname", "reviewer", "User", "deleted")
                .replace("myname/myrepo", "other/repo");

        handler.handle("pull_request_review_comment", "delivery-del3", bytes(payload));

        verify(commentStore, never()).isTracked(anyLong());
        verify(slackNotifier, never()).sendCommentDeleted(any(), anyInt(), anyLong(), any());
    }

    @Test
    void handle는_일반_코멘트_삭제도_issue의_PR번호로_알린다() {
        when(commentStore.isTracked(999L)).thenReturn(true);
        String payload = issueCommentPayload(true, "myname").replace("\"created\"", "\"deleted\"");

        handler.handle("issue_comment", "delivery-del4", bytes(payload));

        verify(slackNotifier).sendCommentDeleted("myname/myrepo", 7, 999L,
                "https://github.com/myname/myrepo/pull/7#issuecomment-999");
    }
}
