package com.pr.automation.github;

import com.pr.automation.config.GithubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GithubClientTest {

    private RestTemplate rt;
    private GithubClient client;

    @BeforeEach
    void setUp() {
        rt = mock(RestTemplate.class);
        client = new GithubClient(rt, new GithubProperties("ghp_token", "me", "secret", null, null));
    }

    @Test
    void 토큰_없으면_모든_조회가_empty() {
        GithubClient disabled = new GithubClient(rt, new GithubProperties("", "me", "secret", null, null));

        assertThat(disabled.fetchFileContent("me/repo", "src/Foo.java", "sha")).isEmpty();
        assertThat(disabled.listDirectory("me/repo", "src", "sha")).isEmpty();
        assertThat(disabled.fetchReviewComment("me/repo", 1L)).isEmpty();
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
    }
}
