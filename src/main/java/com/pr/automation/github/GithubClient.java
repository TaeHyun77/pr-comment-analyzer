package com.pr.automation.github;

import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.GithubProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

// PR 코멘트/파일/디렉터리 조회, PR에 코멘트 게시 등 범용 GitHub API 호출을 담당하는 클래스
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubClient {
    // 일시 오류 재시도 시작 백오프 — GitHub 순단은 대부분 수 초 내 회복되므로 1s→2s로 짧게 잡음.
    // 재시도 횟수(fetchMaxAttempts)는 env로 분리, 이걸로도 안 되는 실패는 복구 사이클(redelivery)이 맡음
    private static final long FETCH_INITIAL_BACKOFF_MS = 1000L;

    private final RestTemplate githubRestTemplate;
    private final GithubProperties githubProperties;

    private int fetchMaxAttempts() {
        return githubProperties.getFetchMaxAttempts();
    }

    // githubProperties에 토큰이 설정돼 있는지로 클라이언트 활성 여부를 판단
    public boolean isEnabled() {
        return StringUtils.hasText(githubProperties.getToken());
    }

    // 리뷰 코멘트 단건을 조회합니다
    // 답글의 부모 코멘트 내용을 가져와 CommentAnalysisService가 스레드 맥락을 구성할 때 사용
    public Optional<FetchedComment> fetchReviewComment(String repoFullName, long commentId) {
        if (!isEnabled()) return Optional.empty();

        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            return Optional.empty();
        }

        try {
            GhComment comment = githubRestTemplate.getForObject(
                    "/repos/{owner}/{repo}/pulls/comments/{id}",
                    GhComment.class,
                    parts[0], parts[1], commentId
            );
            if (comment == null) return Optional.empty();

            String author = comment.getUser() != null && comment.getUser().getLogin() != null
                    ? comment.getUser().getLogin()
                    : "";
            String body = comment.getBody() != null ? comment.getBody() : "";

            return Optional.of(FetchedComment.builder()
                    .id(commentId)
                    .author(author)
                    .body(body)
                    .build()
            );
        } catch (Exception e) {
            log.warn("GitHub 코멘트 조회 실패: {} comment={}", repoFullName, commentId, e);
            return Optional.empty();
        }
    }

    // PR의 현재 head 커밋 SHA 조회 ( issue_comment처럼 페이로드에 head SHA가 없을 때 사용 )
    // 이 PR 브랜치의 최신 커밋 ( 변경이 다 반영된 after 상태 ) 을 조회해, 파일들을 그 기준으로 읽기 위한 것
    public Optional<String> fetchPullHeadSha(String repoFullName, int prNumber) {
        if (!isEnabled()) return Optional.empty();

        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            return Optional.empty();
        }

        try {
            GhPull pull = githubRestTemplate.getForObject(
                    "/repos/{owner}/{repo}/pulls/{number}",
                    GhPull.class,
                    parts[0], parts[1], prNumber
            );
            if (pull == null || pull.getHead() == null || !StringUtils.hasText(pull.getHead().getSha())) {
                return Optional.empty();
            }
            return Optional.of(pull.getHead().getSha());
        } catch (Exception e) {
            log.warn("GitHub PR head SHA 조회 실패: {} #{}", repoFullName, prNumber, e);
            return Optional.empty();
        }
    }

    // 특정 커밋을 기준으로 레포의 파일 하나의 내용을 읽어옴, 파일이 아닌( 디렉터리/심볼릭링크 등 ) 경로면 empty 반환
    public Optional<FetchedFile> fetchFileContent(String repoFullName, String path, String ref) {
        if (!isEnabled()) return Optional.empty();

        String url = buildContentsUrl(repoFullName, path, ref);
        if (url == null) {
            return Optional.empty();
        }
        try {
            GhContent content = githubRestTemplate.getForObject(url, GhContent.class);
            return toFetchedFile(content);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("GitHub 파일 조회 실패: {} path={} ref={}", repoFullName, path, ref, e);
            return Optional.empty();
        }
    }

    // 디렉터리 안의 파일/하위 디렉터리 목록을 가져옵니다. ( path가 빈 문자열이면 레포 루트를 조회 )
    // 에이전트의 list_directory 도구가 이 메서드를 호출합니다.
    public Optional<List<DirEntry>> listDirectory(String repoFullName, String path, String ref) {
        if (!isEnabled()) return Optional.empty();

        String url = buildContentsUrl(repoFullName, path, ref);
        if (url == null) {
            return Optional.empty();
        }
        try {
            GhContent[] entries = githubRestTemplate.getForObject(url, GhContent[].class);
            if (entries == null) {
                return Optional.of(Collections.emptyList());
            }
            List<DirEntry> out = new java.util.ArrayList<>(entries.length);
            for (GhContent c : entries) {
                out.add(DirEntry.builder()
                        .name(c.getName())
                        .path(c.getPath())
                        .type(c.getType())
                        .size(c.getSize() != null ? c.getSize() : 0)
                        .build()
                );
            }
            return Optional.of(out);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("GitHub 디렉터리 조회 실패: {} path={} ref={}", repoFullName, path, ref, e);
            return Optional.empty();
        }
    }

    // PR에서 변경된 파일 목록을 100개까지 조회합니다.
    // CommentAnalysisService.fetchFilePatch가 특정 파일의 전체 diff를 찾을 때, 그리고 PrReviewService가 리뷰 대상 diff 전체를 가져올 때 사용
    //
    // 실패(empty)와 정상적인 빈 결과(빈 리스트)를 구분해 반환합니다 — PR 리뷰 경로는 empty를 실패로 해석해
    // 복구 사이클로 넘기므로, 여기서 실패를 빈 리스트로 뭉개면 리뷰가 완료로 봉인됩니다.
    // 일시 오류(429/5xx/네트워크 순단)는 내부에서 짧게 재시도해 redelivery(분 단위 대기 + 포이즌 예산 소모) 없이 흡수합니다.
    public Optional<List<ChangedFile>> fetchPullFiles(String repoFullName, int prNumber) {
        if (!isEnabled()) return Optional.empty();

        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            return Optional.empty();
        }

        long backoffMillis = FETCH_INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= fetchMaxAttempts(); attempt++) {
            try {
                GhPullFile[] files = githubRestTemplate.getForObject(
                        "/repos/{owner}/{repo}/pulls/{number}/files?per_page=100",
                        GhPullFile[].class,
                        parts[0], parts[1], prNumber);
                return Optional.of(toChangedFiles(files));
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS && !e.getStatusCode().is5xxServerError()) {
                    // 404 등 영구 오류 — 재시도해도 결과가 같으므로 즉시 실패
                    log.warn("GitHub PR 변경 파일 조회 실패({}): {} #{}", e.getStatusCode(), repoFullName, prNumber);
                    return Optional.empty();
                }
                log.warn("GitHub PR 변경 파일 조회 일시 오류({}), 재시도 {}/{}: {} #{}",
                        e.getStatusCode(), attempt, fetchMaxAttempts(), repoFullName, prNumber);
            } catch (ResourceAccessException e) {
                log.warn("GitHub PR 변경 파일 조회 네트워크 오류({}), 재시도 {}/{}: {} #{}",
                        e.getMessage(), attempt, fetchMaxAttempts(), repoFullName, prNumber);
            } catch (Exception e) {
                log.warn("GitHub PR 변경 파일 조회 실패: {} #{}", repoFullName, prNumber, e);
                return Optional.empty();
            }
            if (attempt == fetchMaxAttempts() || !sleepWithFullJitter(backoffMillis)) {
                return Optional.empty();
            }
            backoffMillis *= 2;
        }
        return Optional.empty();
    }

    private static List<ChangedFile> toChangedFiles(GhPullFile[] files) {
        if (files == null) {
            return Collections.emptyList();
        }
        List<ChangedFile> out = new java.util.ArrayList<>(files.length);
        for (GhPullFile f : files) {
            out.add(ChangedFile.builder()
                    .filename(f.getFilename())
                    .status(f.getStatus())
                    .patch(f.getPatch())
                    .additions(f.getAdditions() != null ? f.getAdditions() : 0)
                    .deletions(f.getDeletions() != null ? f.getDeletions() : 0)
                    .build()
            );
        }
        return out;
    }

    // 인터럽트(종료 시그널) 시 false를 반환해 남은 재시도를 중단시킵니다 — 조회 계층은 예외를 던지지 않는다는 규약 유지
    protected boolean sleepWithFullJitter(long maxMillis) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(maxMillis + 1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // PR(=issue)에 마커 문자열을 포함한 코멘트가 이미 게시돼 있는지 확인합니다.
    // 게시 성공 후 완료 기록 전에 크래시해 재진입한 경우의 중복 게시 방지용입니다.
    // 조회 실패는 예외로 전파 — "모르면 게시하지 않는다"(fail-closed)가 중복 방지 목표에 맞고,
    // 아직 게시 전이므로 redelivery 재시도가 안전합니다 (조회는 삼킨다는 다른 메서드들과 기준이 다른 이유)
    public boolean hasIssueCommentWithMarker(String repoFullName, int prNumber, String marker) {
        if (!isEnabled()) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.GITHUB_API_ERROR, "GitHub 토큰 미설정으로 코멘트 조회 불가");
        }
        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.GITHUB_API_ERROR, "레포 식별 불가: " + repoFullName);
        }

        // 봇 코멘트는 PR 초기에 달리므로 보통 1페이지에서 발견됨. 응답이 100건 미만이면 마지막 페이지
        for (int page = 1; ; page++) {
            GhComment[] comments = fetchIssueCommentsPage(parts, prNumber, page);
            if (comments == null || comments.length == 0) {
                return false;
            }
            for (GhComment c : comments) {
                if (c.getBody() != null && c.getBody().contains(marker)) {
                    return true;
                }
            }
            if (comments.length < 100) {
                return false;
            }
        }
    }

    // 이슈 코멘트 한 페이지 조회. 일시 오류(429/5xx/네트워크 순단)는 짧게 재시도하고, 영구 오류/소진 시 예외로 전파
    private GhComment[] fetchIssueCommentsPage(String[] parts, int prNumber, int page) {
        long backoffMillis = FETCH_INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= fetchMaxAttempts(); attempt++) {
            try {
                return githubRestTemplate.getForObject(
                        "/repos/{owner}/{repo}/issues/{number}/comments?per_page=100&page={page}",
                        GhComment[].class,
                        parts[0], parts[1], prNumber, page);
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS && !e.getStatusCode().is5xxServerError()) {
                    throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                            "PR 코멘트 목록 조회 실패(" + e.getStatusCode() + "): " + parts[0] + "/" + parts[1] + " #" + prNumber, e);
                }
                if (attempt == fetchMaxAttempts()) {
                    throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                            "PR 코멘트 목록 조회 재시도 소진: " + parts[0] + "/" + parts[1] + " #" + prNumber, e);
                }
                log.warn("PR 코멘트 목록 조회 일시 오류({}), 재시도 {}/{}: {}/{} #{}",
                        e.getStatusCode(), attempt, fetchMaxAttempts(), parts[0], parts[1], prNumber);
            } catch (ResourceAccessException e) {
                if (attempt == fetchMaxAttempts()) {
                    throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                            "PR 코멘트 목록 조회 재시도 소진: " + parts[0] + "/" + parts[1] + " #" + prNumber, e);
                }
                log.warn("PR 코멘트 목록 조회 네트워크 오류({}), 재시도 {}/{}: {}/{} #{}",
                        e.getMessage(), attempt, fetchMaxAttempts(), parts[0], parts[1], prNumber);
            } catch (RestClientException e) {
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                        "PR 코멘트 목록 조회 실패: " + parts[0] + "/" + parts[1] + " #" + prNumber, e);
            }
            if (!sleepWithFullJitter(backoffMillis)) {
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR, "PR 코멘트 목록 조회 재시도 중 인터럽트 발생");
            }
            backoffMillis *= 2;
        }
        // 도달 불가 - for 안의 모든 경로가 return 또는 throw로 종료됨
        throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR, "PR 코멘트 목록 조회 실패 (루프 이탈)");
    }

    // PR(=issue)에 코멘트를 게시합니다.
    // 일시 오류(429/5xx/네트워크 순단)는 짧게 재시도해 순단 한 번이 redelivery(분 단위 대기 + 포이즌 예산 소모)로
    // 번지는 것을 막고, 권한/유효성 등 영구 오류는 재시도해도 결과가 같으므로 즉시 실패시킵니다
    public void createIssueComment(String repoFullName, int prNumber, String body) {
        if (!isEnabled()) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.GITHUB_API_ERROR, "GitHub 토큰 미설정으로 코멘트 게시 불가");
        }

        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.GITHUB_API_ERROR, "레포 식별 불가: " + repoFullName);
        }

        long backoffMillis = FETCH_INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= fetchMaxAttempts(); attempt++) {
            try {
                githubRestTemplate.postForObject(
                        "/repos/{owner}/{repo}/issues/{number}/comments",
                        Collections.singletonMap("body", body),
                        Void.class,
                        parts[0], parts[1], prNumber);
                return;
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS && !e.getStatusCode().is5xxServerError()) {
                    throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                            "PR 코멘트 게시 실패(" + e.getStatusCode() + "): " + repoFullName + " #" + prNumber, e);
                }
                if (attempt == fetchMaxAttempts()) {
                    throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                            "PR 코멘트 게시 재시도 소진: " + repoFullName + " #" + prNumber, e);
                }
                log.warn("PR 코멘트 게시 일시 오류({}), 재시도 {}/{}: {} #{}",
                        e.getStatusCode(), attempt, fetchMaxAttempts(), repoFullName, prNumber);
            } catch (ResourceAccessException e) {
                if (attempt == fetchMaxAttempts()) {
                    throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR,
                            "PR 코멘트 게시 재시도 소진: " + repoFullName + " #" + prNumber, e);
                }
                log.warn("PR 코멘트 게시 네트워크 오류({}), 재시도 {}/{}: {} #{}",
                        e.getMessage(), attempt, fetchMaxAttempts(), repoFullName, prNumber);
            } catch (RestClientException e) {
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR, "PR 코멘트 게시 실패: " + repoFullName + " #" + prNumber, e);
            }
            if (!sleepWithFullJitter(backoffMillis)) {
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR, "PR 코멘트 게시 재시도 중 인터럽트 발생");
            }
            backoffMillis *= 2;
        }
        // 도달 불가 - for 안의 모든 경로가 return 또는 throw로 종료됨
        throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR, "PR 코멘트 게시 실패 (루프 이탈)");
    }

    // path를 직접 URL에 넣어 슬래시를 보존 (DefaultUriBuilderFactory의 path-var 인코딩 회피)
    private static String buildContentsUrl(String repoFullName, String path, String ref) {
        String[] parts = splitRepo(repoFullName);
        if (parts == null || path == null) {
            return null;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        StringBuilder sb = new StringBuilder("/repos/").append(parts[0]).append('/').append(parts[1]).append("/contents");
        if (!normalized.isEmpty()) {
            sb.append('/').append(normalized);
        }
        if (StringUtils.hasText(ref)) {
            sb.append("?ref=").append(ref);
        }
        return sb.toString();
    }

    private static String[] splitRepo(String repoFullName) {
        if (!StringUtils.hasText(repoFullName)) {
            return null;
        }
        String[] parts = repoFullName.split("/", 2);
        return parts.length == 2 ? parts : null;
    }

    private static Optional<FetchedFile> toFetchedFile(GhContent content) {
        if (content == null || !"file".equalsIgnoreCase(content.getType())) {
            return Optional.empty();
        }
        String decoded = decode(content.getContent(), content.getEncoding());
        return Optional.of(FetchedFile.builder()
                .path(content.getPath())
                .size(content.getSize() != null ? content.getSize() : 0)
                .content(decoded)
                .build());
    }

    private static String decode(String raw, String encoding) {
        if (raw == null) {
            return "";
        }
        if ("base64".equalsIgnoreCase(encoding)) {
            // GitHub은 base64 줄바꿈을 포함해 줄 수 있으므로 MIME 디코더 사용.
            try {
                byte[] bytes = Base64.getMimeDecoder().decode(raw);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return raw;
            }
        }
        return raw;
    }

    // --- 공개 결과 DTO ---

    @Getter
    @Builder
    @AllArgsConstructor
    public static class FetchedComment {
        private final long id;
        private final String author;
        private final String body;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class FetchedFile {
        private final String path;
        private final int size;
        private final String content;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class DirEntry {
        private final String name;
        private final String path;
        private final String type; // "file" | "dir" | "symlink" | "submodule"
        private final int size;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class ChangedFile {
        private final String filename;
        private final String status;  // "added" | "modified" | "removed" | "renamed" 등
        private final String patch;   // unified diff (바이너리/대용량이면 null일 수 있음)
        private final int additions;
        private final int deletions;
    }

    // --- Jackson 매핑용 GitHub 응답 DTO ---

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GhComment {
        private String body;
        private GhUser user;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GhUser {
        private String login;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GhPull {
        private GhHead head;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GhHead {
        private String sha;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class GhContent {
        private String name;
        private String path;
        private String type;
        private Integer size;
        private String content;
        private String encoding;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class GhPullFile {
        private String filename;
        private String status;
        private String patch;
        private Integer additions;
        private Integer deletions;
    }
}
