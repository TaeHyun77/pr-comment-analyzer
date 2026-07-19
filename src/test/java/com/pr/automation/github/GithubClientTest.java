package com.pr.automation.github;

import com.pr.automation.config.GithubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GithubClientTest {

    private static final String PULL_FILES_URL = "/repos/{owner}/{repo}/pulls/{number}/files?per_page=100";
    private static final String ISSUE_COMMENTS_URL = "/repos/{owner}/{repo}/issues/{number}/comments?per_page=100&page={page}";
    private static final String CREATE_COMMENT_URL = "/repos/{owner}/{repo}/issues/{number}/comments";
    private static final String MARKER = "<!-- pr-automation:pr-review -->";

    private RestTemplate rt;
    private GithubClient client;
    private AtomicInteger sleepCount;

    @BeforeEach
    void setUp() {
        rt = mock(RestTemplate.class);
        sleepCount = new AtomicInteger();
        client = new TestGithubClient(rt, new GithubProperties("ghp_token", "me", "secret", null, null, 3, 10000), sleepCount);
    }

    @Test
    void 토큰_없으면_모든_조회가_empty() {
        GithubClient disabled = new GithubClient(rt, new GithubProperties("", "me", "secret", null, null, 3, 10000));

        assertThat(disabled.fetchFileContent("me/repo", "src/Foo.java", "sha")).isEmpty();
        assertThat(disabled.listDirectory("me/repo", "src", "sha")).isEmpty();
        assertThat(disabled.fetchReviewComment("me/repo", 1L)).isEmpty();
        assertThat(disabled.fetchPullFiles("me/repo", 7)).isEmpty();
    }

    @Test
    void fetchFileContent_정상_파일을_base64_디코드해서_반환한다() {
        String encoded = Base64.getEncoder().encodeToString("hello world".getBytes(StandardCharsets.UTF_8));
        GithubClient.GhContent gh = new GithubClient.GhContent();
        gh.setType("file");
        gh.setPath("src/Foo.java");
        gh.setSize(11);
        gh.setContent(encoded);
        gh.setEncoding("base64");
        when(rt.getForObject(eq("/repos/me/repo/contents/src/Foo.java?ref=abc123"), eq(GithubClient.GhContent.class)))
                .thenReturn(gh);

        Optional<GithubClient.FetchedFile> result = client.fetchFileContent("me/repo", "src/Foo.java", "abc123");

        assertThat(result).isPresent();
        assertThat(result.get().getPath()).isEqualTo("src/Foo.java");
        assertThat(result.get().getContent()).isEqualTo("hello world");
        assertThat(result.get().getSize()).isEqualTo(11);
    }

    @Test
    void fetchFileContent_파일이_아닌_타입은_empty() {
        GithubClient.GhContent gh = new GithubClient.GhContent();
        gh.setType("dir");
        when(rt.getForObject(eq("/repos/me/repo/contents/src/Foo.java?ref=abc123"), eq(GithubClient.GhContent.class)))
                .thenReturn(gh);

        assertThat(client.fetchFileContent("me/repo", "src/Foo.java", "abc123")).isEmpty();
    }

    @Test
    void fetchFileContent_404면_empty_반환_예외_안_던짐() {
        when(rt.getForObject(eq("/repos/me/repo/contents/nope.java?ref=abc123"), eq(GithubClient.GhContent.class)))
                .thenThrow(HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThat(client.fetchFileContent("me/repo", "nope.java", "abc123")).isEmpty();
    }

    @Test
    void fetchFileContent_ref가_null이면_쿼리스트링_없이_호출한다() {
        GithubClient.GhContent gh = new GithubClient.GhContent();
        gh.setType("file");
        gh.setPath("src/Foo.java");
        gh.setContent(Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)));
        gh.setEncoding("base64");
        when(rt.getForObject(eq("/repos/me/repo/contents/src/Foo.java"), eq(GithubClient.GhContent.class)))
                .thenReturn(gh);

        assertThat(client.fetchFileContent("me/repo", "src/Foo.java", null)).isPresent();
    }

    @Test
    void fetchFileContent_경로_앞_슬래시는_정규화한다() {
        GithubClient.GhContent gh = new GithubClient.GhContent();
        gh.setType("file");
        gh.setContent(Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)));
        gh.setEncoding("base64");
        when(rt.getForObject(eq("/repos/me/repo/contents/src/Foo.java?ref=sha"), eq(GithubClient.GhContent.class)))
                .thenReturn(gh);

        assertThat(client.fetchFileContent("me/repo", "/src/Foo.java", "sha")).isPresent();
    }

    @Test
    void listDirectory_엔트리_변환() {
        GithubClient.GhContent a = new GithubClient.GhContent();
        a.setName("Foo.java");
        a.setPath("src/Foo.java");
        a.setType("file");
        a.setSize(100);
        GithubClient.GhContent b = new GithubClient.GhContent();
        b.setName("sub");
        b.setPath("src/sub");
        b.setType("dir");
        when(rt.getForObject(eq("/repos/me/repo/contents/src?ref=sha"), eq(GithubClient.GhContent[].class)))
                .thenReturn(new GithubClient.GhContent[] {a, b});

        Optional<List<GithubClient.DirEntry>> result = client.listDirectory("me/repo", "src", "sha");

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0).getName()).isEqualTo("Foo.java");
        assertThat(result.get().get(0).getSize()).isEqualTo(100);
        assertThat(result.get().get(1).getType()).isEqualTo("dir");
    }

    @Test
    void listDirectory_빈_path는_레포_루트로_요청한다() {
        when(rt.getForObject(eq("/repos/me/repo/contents?ref=sha"), eq(GithubClient.GhContent[].class)))
                .thenReturn(new GithubClient.GhContent[0]);

        Optional<List<GithubClient.DirEntry>> result = client.listDirectory("me/repo", "", "sha");

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void splitRepo_형식_불량은_empty() {
        assertThat(client.fetchFileContent("invalid", "x", "sha")).isEmpty();
        assertThat(client.listDirectory("", "x", "sha")).isEmpty();
        assertThat(client.fetchPullFiles("invalid", 7)).isEmpty();
    }

    @Test
    void fetchPullFiles_정상조회는_변경파일_리스트를_반환한다() {
        GithubClient.GhPullFile f = new GithubClient.GhPullFile();
        f.setFilename("src/Foo.java");
        f.setStatus("modified");
        f.setPatch("@@ -1 +1 @@\n+x");
        f.setAdditions(2);
        f.setDeletions(1);
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7)))
                .thenReturn(new GithubClient.GhPullFile[] {f});

        Optional<List<GithubClient.ChangedFile>> result = client.fetchPullFiles("me/repo", 7);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).getFilename()).isEqualTo("src/Foo.java");
        assertThat(result.get().get(0).getPatch()).isEqualTo("@@ -1 +1 @@\n+x");
        assertThat(sleepCount.get()).isZero();
    }

    @Test
    void fetchPullFiles_일시오류는_재시도후_성공하면_리스트를_반환한다() {
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7)))
                .thenThrow(serverError(HttpStatus.SERVICE_UNAVAILABLE))
                .thenThrow(clientError(HttpStatus.TOO_MANY_REQUESTS))
                .thenReturn(new GithubClient.GhPullFile[0]);

        Optional<List<GithubClient.ChangedFile>> result = client.fetchPullFiles("me/repo", 7);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
        verify(rt, times(3)).getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7));
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void fetchPullFiles_일시오류가_계속되면_재시도_소진후_empty() {
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7)))
                .thenThrow(serverError(HttpStatus.BAD_GATEWAY));

        assertThat(client.fetchPullFiles("me/repo", 7)).isEmpty();
        verify(rt, times(3)).getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7));
        // 마지막 시도 후엔 sleep 없이 즉시 종료
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void fetchPullFiles_영구오류_404는_재시도없이_즉시_empty() {
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7)))
                .thenThrow(clientError(HttpStatus.NOT_FOUND));

        assertThat(client.fetchPullFiles("me/repo", 7)).isEmpty();
        verify(rt, times(1)).getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7));
        assertThat(sleepCount.get()).isZero();
    }

    @Test
    void fetchPullFiles_네트워크오류_모두_실패시_empty() {
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        assertThat(client.fetchPullFiles("me/repo", 7)).isEmpty();
        verify(rt, times(3)).getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7));
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void createIssueComment_일시오류는_재시도후_성공한다() {
        when(rt.postForObject(eq(CREATE_COMMENT_URL), any(), eq(Void.class), eq("me"), eq("repo"), eq(7)))
                .thenThrow(serverError(HttpStatus.SERVICE_UNAVAILABLE))
                .thenThrow(new ResourceAccessException("read timed out"))
                .thenReturn(null);

        client.createIssueComment("me/repo", 7, "본문");

        verify(rt, times(3)).postForObject(eq(CREATE_COMMENT_URL), any(), eq(Void.class), eq("me"), eq("repo"), eq(7));
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void createIssueComment_영구오류는_재시도없이_즉시_예외() {
        // 권한 누락(403) 등은 재시도해도 결과가 같음 — 낭비 사이클 없이 즉시 실패해야 함
        when(rt.postForObject(eq(CREATE_COMMENT_URL), any(), eq(Void.class), eq("me"), eq("repo"), eq(7)))
                .thenThrow(clientError(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.createIssueComment("me/repo", 7, "본문"))
                .isInstanceOf(com.pr.automation.common.error.AutomationException.class);
        verify(rt, times(1)).postForObject(eq(CREATE_COMMENT_URL), any(), eq(Void.class), eq("me"), eq("repo"), eq(7));
        assertThat(sleepCount.get()).isZero();
    }

    @Test
    void createIssueComment_일시오류가_계속되면_재시도_소진후_예외() {
        when(rt.postForObject(eq(CREATE_COMMENT_URL), any(), eq(Void.class), eq("me"), eq("repo"), eq(7)))
                .thenThrow(serverError(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.createIssueComment("me/repo", 7, "본문"))
                .isInstanceOf(com.pr.automation.common.error.AutomationException.class);
        verify(rt, times(3)).postForObject(eq(CREATE_COMMENT_URL), any(), eq(Void.class), eq("me"), eq("repo"), eq(7));
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void hasIssueCommentWithMarker_첫페이지에서_마커를_찾으면_true() {
        when(rt.getForObject(eq(ISSUE_COMMENTS_URL), eq(GithubClient.GhComment[].class), eq("me"), eq("repo"), eq(7), eq(1)))
                .thenReturn(new GithubClient.GhComment[] {
                        comment("일반 코멘트"),
                        comment(MARKER + "\n## 자동 PR 리뷰")});

        assertThat(client.hasIssueCommentWithMarker("me/repo", 7, MARKER)).isTrue();
    }

    @Test
    void hasIssueCommentWithMarker_마커가_없으면_false_마지막페이지에서_중단() {
        when(rt.getForObject(eq(ISSUE_COMMENTS_URL), eq(GithubClient.GhComment[].class), eq("me"), eq("repo"), eq(7), eq(1)))
                .thenReturn(new GithubClient.GhComment[] {comment("일반 코멘트")});

        assertThat(client.hasIssueCommentWithMarker("me/repo", 7, MARKER)).isFalse();
        // 응답이 100건 미만 = 마지막 페이지 — 다음 페이지를 조회하지 않음
        verify(rt, times(1)).getForObject(eq(ISSUE_COMMENTS_URL), eq(GithubClient.GhComment[].class), eq("me"), eq("repo"), eq(7), any());
    }

    @Test
    void hasIssueCommentWithMarker_100건이면_다음_페이지까지_조회한다() {
        GithubClient.GhComment[] fullPage = new GithubClient.GhComment[100];
        for (int i = 0; i < 100; i++) {
            fullPage[i] = comment("코멘트 " + i);
        }
        when(rt.getForObject(eq(ISSUE_COMMENTS_URL), eq(GithubClient.GhComment[].class), eq("me"), eq("repo"), eq(7), eq(1)))
                .thenReturn(fullPage);
        when(rt.getForObject(eq(ISSUE_COMMENTS_URL), eq(GithubClient.GhComment[].class), eq("me"), eq("repo"), eq(7), eq(2)))
                .thenReturn(new GithubClient.GhComment[] {comment(MARKER)});

        assertThat(client.hasIssueCommentWithMarker("me/repo", 7, MARKER)).isTrue();
    }

    @Test
    void hasIssueCommentWithMarker_조회_영구오류는_예외로_전파() {
        // "모르면 게시하지 않는다"(fail-closed) — 조회 실패를 false로 뭉개면 중복 게시로 이어짐
        when(rt.getForObject(eq(ISSUE_COMMENTS_URL), eq(GithubClient.GhComment[].class), eq("me"), eq("repo"), eq(7), eq(1)))
                .thenThrow(clientError(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.hasIssueCommentWithMarker("me/repo", 7, MARKER))
                .isInstanceOf(com.pr.automation.common.error.AutomationException.class);
        assertThat(sleepCount.get()).isZero();
    }

    // --- 헬퍼 ---

    private static GithubClient.GhComment comment(String body) {
        GithubClient.GhComment c = new GithubClient.GhComment();
        c.setBody(body);
        return c;
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static HttpServerErrorException serverError(HttpStatus status) {
        return HttpServerErrorException.create(status, status.getReasonPhrase(), null,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /**
     * 재시도 백오프 sleep을 no-op으로 만들어 테스트 시간을 단축. 호출 횟수만 카운트.
     */
    private static class TestGithubClient extends GithubClient {
        private final AtomicInteger counter;

        TestGithubClient(RestTemplate rt, GithubProperties props, AtomicInteger counter) {
            super(rt, props);
            this.counter = counter;
        }

        @Override
        protected boolean sleepWithFullJitter(long maxMillis) {
            counter.incrementAndGet();
            return true;
        }
    }
}
