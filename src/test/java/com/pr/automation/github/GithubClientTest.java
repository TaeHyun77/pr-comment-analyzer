package com.pr.automation.github;

import com.pr.automation.config.properties.GithubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GithubClientTest {

    private static final String PULL_FILES_URL = "/repos/{owner}/{repo}/pulls/{number}/files?per_page=100&page={page}";

    private RestTemplate rt;
    private GithubClient client;
    private AtomicInteger sleepCount;

    @BeforeEach
    void setUp() {
        rt = mock(RestTemplate.class);
        sleepCount = new AtomicInteger();
        client = new TestGithubClient(rt, new GithubProperties("ghp_token", "me", "secret", null, 3, 10000), sleepCount);
    }

    @Test
    void 토큰_없으면_모든_조회가_empty() {
        GithubClient disabled = new GithubClient(rt, new GithubProperties("", "me", "secret", null, 3, 10000));

        assertThat(disabled.fetchReviewComment("me/repo", 1L)).isEmpty();
        assertThat(disabled.fetchPullFiles("me/repo", 7)).isEmpty();
    }

    @Test
    void splitRepo_형식_불량은_empty() {
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
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1)))
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
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1)))
                .thenThrow(serverError(HttpStatus.SERVICE_UNAVAILABLE))
                .thenThrow(clientError(HttpStatus.TOO_MANY_REQUESTS))
                .thenReturn(new GithubClient.GhPullFile[0]);

        Optional<List<GithubClient.ChangedFile>> result = client.fetchPullFiles("me/repo", 7);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
        verify(rt, times(3)).getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1));
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void fetchPullFiles_일시오류가_계속되면_재시도_소진후_empty() {
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1)))
                .thenThrow(serverError(HttpStatus.BAD_GATEWAY));

        assertThat(client.fetchPullFiles("me/repo", 7)).isEmpty();
        verify(rt, times(3)).getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1));
        // 마지막 시도 후엔 sleep 없이 즉시 종료
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void fetchPullFiles_영구오류_404는_재시도없이_즉시_empty() {
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1)))
                .thenThrow(clientError(HttpStatus.NOT_FOUND));

        assertThat(client.fetchPullFiles("me/repo", 7)).isEmpty();
        verify(rt, times(1)).getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1));
        assertThat(sleepCount.get()).isZero();
    }

    @Test
    void fetchPullFiles_네트워크오류_모두_실패시_empty() {
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        assertThat(client.fetchPullFiles("me/repo", 7)).isEmpty();
        verify(rt, times(3)).getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1));
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void fetchPullFiles_100개를_넘으면_다음_페이지까지_모두_조회한다() {
        GithubClient.GhPullFile[] firstPage = new GithubClient.GhPullFile[100];
        for (int i = 0; i < 100; i++) {
            firstPage[i] = new GithubClient.GhPullFile();
            firstPage[i].setFilename("src/F" + i + ".java");
        }
        GithubClient.GhPullFile last = new GithubClient.GhPullFile();
        last.setFilename("src/Last.java");
        last.setPatch("@@ -1 +1 @@\n+z");
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1)))
                .thenReturn(firstPage);
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(2)))
                .thenReturn(new GithubClient.GhPullFile[] {last});

        Optional<List<GithubClient.ChangedFile>> result = client.fetchPullFiles("me/repo", 7);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(101);
        // 101번째 파일의 patch가 누락되던 문제의 회귀 방지
        assertThat(result.get().get(100).getFilename()).isEqualTo("src/Last.java");
        assertThat(result.get().get(100).getPatch()).isEqualTo("@@ -1 +1 @@\n+z");
    }

    @Test
    void fetchPullFiles_다음_페이지_조회가_실패하면_전체를_empty로_처리한다() {
        GithubClient.GhPullFile[] firstPage = new GithubClient.GhPullFile[100];
        for (int i = 0; i < 100; i++) {
            firstPage[i] = new GithubClient.GhPullFile();
        }
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(1)))
                .thenReturn(firstPage);
        when(rt.getForObject(eq(PULL_FILES_URL), eq(GithubClient.GhPullFile[].class), eq("me"), eq("repo"), eq(7), eq(2)))
                .thenThrow(clientError(HttpStatus.NOT_FOUND));

        assertThat(client.fetchPullFiles("me/repo", 7)).isEmpty();
    }

    // --- 헬퍼 ---

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
