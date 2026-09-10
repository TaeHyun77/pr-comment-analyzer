package com.pr.automation.github;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 네트워크 없이 검증하기 위해 로컬에 만든 git 저장소를 원격처럼 사용한다
class RepoCheckoutTest {

    @Test
    void close하면_체크아웃_디렉터리가_지워진다() throws Exception {
        Path origin = createLocalRepo();
        String sha = headSha(origin);
        Path dir;

        try (RepoCheckout checkout = fetchFromLocal(origin, sha)) {
            dir = checkout.dir();
            assertThat(Files.exists(dir.resolve("hello.txt"))).isTrue();
        }

        assertThat(Files.exists(dir)).isFalse();
        deleteRecursively(origin);
    }

    @Test
    void headSha가_비면_예외를_던진다() {
        assertThatThrownBy(() -> RepoCheckout.fetch("me/repo", "  ", null))
                .hasMessageContaining("체크아웃할 커밋 SHA가 없음");
    }

    @Test
    void 존재하지_않는_커밋이면_디렉터리를_남기지_않는다() throws Exception {
        Path origin = createLocalRepo();
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        long before = countCheckoutDirs(tmp);

        assertThatThrownBy(() -> fetchFromLocal(origin, "0000000000000000000000000000000000000000"))
                .hasMessageContaining("git fetch 실패");

        assertThat(countCheckoutDirs(tmp)).isEqualTo(before);
        deleteRecursively(origin);
    }

    @Test
    void cleanupOrphans는_접두사가_같은_디렉터리를_지운다() throws Exception {
        Path orphan = Files.createTempDirectory(RepoCheckout.DIR_PREFIX);
        Files.write(orphan.resolve("leftover.txt"), "x".getBytes(StandardCharsets.UTF_8));

        RepoCheckout.cleanupOrphans();

        assertThat(Files.exists(orphan)).isFalse();
    }

    // ---- 헬퍼 ----

    // fetch()는 github.com URL을 조립하므로, 로컬 원격 경로는 fetchFrom()으로 직접 넘긴다
    private static RepoCheckout fetchFromLocal(Path origin, String sha) {
        return RepoCheckout.fetchFrom(origin.toAbsolutePath().toString(), sha);
    }

    private static Path createLocalRepo() throws Exception {
        Path repo = Files.createTempDirectory("repo-checkout-origin-");
        run(repo, "git", "init", "--quiet", ".");
        run(repo, "git", "config", "user.email", "test@example.com");
        run(repo, "git", "config", "user.name", "test");
        Files.write(repo.resolve("hello.txt"), "hi".getBytes(StandardCharsets.UTF_8));
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "--quiet", "-m", "init");
        return repo;
    }

    private static String headSha(Path repo) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", "HEAD").directory(repo.toFile()).start();
        String sha = new java.util.Scanner(p.getInputStream(), "UTF-8").useDelimiter("\\A").next().trim();
        p.waitFor();
        return sha;
    }

    private static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        new java.util.Scanner(p.getInputStream(), "UTF-8").useDelimiter("\\A").hasNext();
        p.waitFor();
    }

    private static long countCheckoutDirs(Path tmp) throws IOException {
        try (Stream<Path> s = Files.list(tmp)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(RepoCheckout.DIR_PREFIX))
                    .count();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }
}
