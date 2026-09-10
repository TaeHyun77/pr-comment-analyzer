package com.pr.automation.github;

import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 분석 대상 커밋을 임시 디렉터리에 받아두는 일회용 체크아웃입니다.
 * 에이전트가 로컬 파일로 저장소를 탐색할 수 있게 하는 것이 목적이며, close 시 디렉터리를 지웁니다.
 *
 * 브랜치가 아니라 커밋 SHA로 받습니다 — 브랜치 tip은 코멘트가 달린 뒤에도 움직이므로
 * 브랜치로 받으면 코멘트가 가리키는 라인과 실제 코드가 어긋날 수 있습니다.
 */
@Slf4j
// final을 두지 않는다 — 서비스 테스트에서 체크아웃을 대역으로 바꿔 끼울 수 있어야 한다
public class RepoCheckout implements AutoCloseable {

    // 임시 디렉터리 접두사 - 기동 시 고아 디렉터리를 찾아 지우는 기준이기도 하다
    public static final String DIR_PREFIX = "pr-analysis-";

    private static final long GIT_TIMEOUT_SECONDS = 120L;

    private final Path dir;

    private RepoCheckout(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /**
     * 지정한 커밋 하나만 얕게 받아옵니다. 히스토리와 다른 브랜치는 받지 않습니다.
     * token이 비어 있으면 익명으로 받으므로 공개 저장소만 가능합니다.
     */
    public static RepoCheckout fetch(String repoFullName, String headSha, String token) {
        if (!StringUtils.hasText(headSha)) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REPO_NOT_READABLE,
                    "체크아웃할 커밋 SHA가 없음: " + repoFullName);
        }
        // 토큰이 원격 URL에 박히므로 이 URL은 로그에 남기지 않는다
        String remote = StringUtils.hasText(token)
                ? "https://x-access-token:" + token + "@github.com/" + repoFullName + ".git"
                : "https://github.com/" + repoFullName + ".git";
        return fetchFrom(remote, headSha);
    }

    // 패키지 접근 — 테스트에서 로컬 저장소를 원격으로 지정하기 위해 분리
    static RepoCheckout fetchFrom(String remote, String headSha) {
        Path dir;
        try {
            dir = Files.createTempDirectory(DIR_PREFIX);
        } catch (IOException e) {
            throw new AutomationException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.REPO_NOT_READABLE,
                    "체크아웃 디렉터리 생성 실패: " + e.getMessage());
        }

        RepoCheckout checkout = new RepoCheckout(dir);
        try {
            run(dir, "git", "init", "--quiet", ".");
            run(dir, "git", "remote", "add", "origin", remote);
            run(dir, "git", "fetch", "--quiet", "--depth", "1", "origin", headSha);
            run(dir, "git", "checkout", "--quiet", "FETCH_HEAD");

            log.debug("체크아웃 완료: {} → {}", headSha.substring(0, Math.min(7, headSha.length())), dir);
            return checkout;
        } catch (RuntimeException e) {
            checkout.close();
            throw e;
        }
    }

    @Override
    public void close() {
        deleteRecursively(dir);
    }

    /** 기동 시 이전 프로세스가 남긴 체크아웃 디렉터리를 정리합니다. 삭제한 개수를 반환합니다. */
    public static int cleanupOrphans() {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        int removed = 0;
        try (Stream<Path> entries = Files.list(tmp)) {
            List<Path> orphans = entries
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(DIR_PREFIX))
                    .collect(Collectors.toList());
            for (Path orphan : orphans) {
                deleteRecursively(orphan);
                removed++;
            }
        } catch (IOException e) {
            log.warn("체크아웃 고아 디렉터리 정리 실패", e);
        }
        return removed;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 개별 파일 삭제 실패는 무시 - 남은 파일은 다음 기동의 고아 정리가 맡는다
                }
            });
        } catch (IOException e) {
            log.warn("체크아웃 디렉터리 삭제 실패: {}", root, e);
        }
    }

    // git 명령 실행. 실패 시 stderr를 메시지에 담되 토큰이 섞일 수 있는 원격 URL은 노출하지 않는다
    private static void run(Path dir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null"));
            Process process = pb.start();

            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AutomationException(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.REPO_NOT_READABLE,
                        "git " + command[1] + " 타임아웃(" + GIT_TIMEOUT_SECONDS + "초)");
            }
            if (process.exitValue() != 0) {
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.REPO_NOT_READABLE,
                        "git " + command[1] + " 실패 (종료 코드 " + process.exitValue() + ")");
            }
        } catch (IOException e) {
            throw new AutomationException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.REPO_NOT_READABLE,
                    "git 실행 실패(" + Arrays.toString(new String[]{command[0], command[1]}) + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AutomationException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.REPO_NOT_READABLE,
                    "git 실행 중 인터럽트");
        }
    }
}
