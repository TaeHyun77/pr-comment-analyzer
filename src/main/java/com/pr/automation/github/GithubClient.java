package com.pr.automation.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pr.automation.config.properties.GithubProperties;
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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

// PR 코멘트/변경 파일/PR 메타데이터 조회, PR에 코멘트 게시 등 범용 GitHub API 호출을 담당하는 클래스
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubClient {
    private final RestTemplate githubRestTemplate;
    private final GithubProperties githubProperties;

    // 일시 오류 재시도 시작 백오프 - GitHub 순단은 대부분 수 초 내 회복되므로 1s→2s로 짧게 잡음
    // 재시도 횟수는 env로 분리, 소진 후 실패는 호출자 몫
    private static final long FETCH_INITIAL_BACKOFF_MS = 1000L;

    private int fetchMaxAttempts() {
        return githubProperties.getFetchMaxAttempts();
    }

    // githubProperties에 토큰이 설정돼 있는지로 클라이언트 활성 여부를 판단
    public boolean isEnabled() {
        return StringUtils.hasText(githubProperties.getToken());
    }

    // 리뷰 코멘트 단건을 조회합니다.
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
                    .inReplyToId(comment.getInReplyToId())
                    .build()
            );
        } catch (Exception e) {
            log.warn("GitHub 코멘트 조회 실패: {} comment={}", repoFullName, commentId, e);
            return Optional.empty();
        }
    }

    // PR의 리뷰 코멘트 전체를 페이지 단위로 조회
    public Optional<List<FetchedComment>> fetchPullReviewComments(String repoFullName, int prNumber) {
        if (!isEnabled()) return Optional.empty();

        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            return Optional.empty();
        }

        List<FetchedComment> all = new java.util.ArrayList<>();
        // 응답이 100건 미만이면 마지막 페이지
        for (int page = 1; ; page++) {
            GhComment[] comments = fetchReviewCommentsPage(parts, prNumber, page);
            if (comments == null) {
                return Optional.empty(); // 조회 실패 - 부분 결과는 루트 누락 위험이 있어 통째로 실패 처리
            }
            if (comments.length == 0) {
                break;
            }
            for (GhComment c : comments) {
                if (c.getId() == null) continue; // id 없는 응답은 스레드 재구성에 쓸 수 없으므로 제외
                all.add(FetchedComment.builder()
                        .id(c.getId())
                        .author(c.getUser() != null && c.getUser().getLogin() != null ? c.getUser().getLogin() : "")
                        .body(c.getBody() != null ? c.getBody() : "")
                        .inReplyToId(c.getInReplyToId())
                        .pullRequestReviewId(c.getPullRequestReviewId())
                        .htmlUrl(c.getHtmlUrl())
                        .filePath(c.getPath())
                        .line(c.getLine())
                        .side(c.getSide())
                        .startLine(c.getStartLine())
                        .originalLine(c.getOriginalLine())
                        .subjectType(c.getSubjectType())
                        .build());
            }
            if (comments.length < 100) {
                break;
            }
        }
        return Optional.of(all);
    }

    // 리뷰 코멘트 한 페이지 조회
    private GhComment[] fetchReviewCommentsPage(String[] parts, int prNumber, int page) {
        long backoffMillis = FETCH_INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= fetchMaxAttempts(); attempt++) {
            try {
                return githubRestTemplate.getForObject(
                        "/repos/{owner}/{repo}/pulls/{number}/comments?per_page=100&page={page}",
                        GhComment[].class,
                        parts[0], parts[1], prNumber, page);
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS && !e.getStatusCode().is5xxServerError()) {
                    log.warn("PR 리뷰 코멘트 목록 조회 실패({}): {}/{} #{} page={}", e.getStatusCode(), parts[0], parts[1], prNumber, page);
                    return null;
                }
                log.warn("PR 리뷰 코멘트 목록 조회 일시 오류({}), 재시도 {}/{}: {}/{} #{}",
                        e.getStatusCode(), attempt, fetchMaxAttempts(), parts[0], parts[1], prNumber);
            } catch (ResourceAccessException e) {
                log.warn("PR 리뷰 코멘트 목록 조회 네트워크 오류({}), 재시도 {}/{}: {}/{} #{}",
                        e.getMessage(), attempt, fetchMaxAttempts(), parts[0], parts[1], prNumber);
            } catch (Exception e) {
                log.warn("PR 리뷰 코멘트 목록 조회 실패: {}/{} #{}", parts[0], parts[1], prNumber, e);
                return null;
            }
            if (attempt == fetchMaxAttempts() || !sleepWithFullJitter(backoffMillis)) {
                return null;
            }
            backoffMillis *= 2;
        }
        return null;
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

    // PR의 변경 파일 전체를 페이지 단위로 조회 - 한 페이지(100개)만 보면 그 뒤 파일의 patch가 누락된다
    public Optional<List<ChangedFile>> fetchPullFiles(String repoFullName, int prNumber) {
        if (!isEnabled()) return Optional.empty();

        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            return Optional.empty();
        }

        List<ChangedFile> all = new java.util.ArrayList<>();
        // 응답이 100건 미만이면 마지막 페이지
        for (int page = 1; ; page++) {
            GhPullFile[] files = fetchPullFilesPage(parts, prNumber, page);
            if (files == null) {
                return Optional.empty(); // 조회 실패 - 부분 결과는 대상 파일 누락 여부를 알 수 없어 통째로 실패 처리
            }
            all.addAll(toChangedFiles(files));
            if (files.length < 100) {
                break;
            }
        }
        return Optional.of(all);
    }

    // 변경 파일 한 페이지 조회, 실패 시 null
    private GhPullFile[] fetchPullFilesPage(String[] parts, int prNumber, int page) {
        long backoffMillis = FETCH_INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= fetchMaxAttempts(); attempt++) {
            try {
                GhPullFile[] files = githubRestTemplate.getForObject(
                        "/repos/{owner}/{repo}/pulls/{number}/files?per_page=100&page={page}",
                        GhPullFile[].class,
                        parts[0], parts[1], prNumber, page);
                return files != null ? files : new GhPullFile[0];
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS && !e.getStatusCode().is5xxServerError()) {
                    // 404 등 영구 오류 — 재시도해도 결과가 같으므로 즉시 실패
                    log.warn("GitHub PR 변경 파일 조회 실패({}): {}/{} #{} page={}", e.getStatusCode(), parts[0], parts[1], prNumber, page);
                    return null;
                }
                log.warn("GitHub PR 변경 파일 조회 일시 오류({}), 재시도 {}/{}: {}/{} #{} page={}",
                        e.getStatusCode(), attempt, fetchMaxAttempts(), parts[0], parts[1], prNumber, page);
            } catch (ResourceAccessException e) {
                log.warn("GitHub PR 변경 파일 조회 네트워크 오류({}), 재시도 {}/{}: {}/{} #{} page={}",
                        e.getMessage(), attempt, fetchMaxAttempts(), parts[0], parts[1], prNumber, page);
            } catch (Exception e) {
                log.warn("GitHub PR 변경 파일 조회 실패: {}/{} #{} page={}", parts[0], parts[1], prNumber, page, e);
                return null;
            }
            if (attempt == fetchMaxAttempts() || !sleepWithFullJitter(backoffMillis)) {
                return null;
            }
            backoffMillis *= 2;
        }
        return null;
    }

    private static List<ChangedFile> toChangedFiles(GhPullFile[] files) {
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

    protected boolean sleepWithFullJitter(long maxMillis) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(maxMillis + 1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String[] splitRepo(String repoFullName) {
        if (!StringUtils.hasText(repoFullName)) {
            return null;
        }
        String[] parts = repoFullName.split("/", 2);
        return parts.length == 2 ? parts : null;
    }

    // --- 공개 결과 DTO ---

    // 스레드 재구성에는 id/author/body/inReplyToId만 쓰이고, 위치 필드와 리뷰 id는 리뷰 단위 분석에서 CommentEvent를 만들 때 쓰인다
    @Getter
    @Builder
    @AllArgsConstructor
    public static class FetchedComment {
        private final long id;
        private final String author;
        private final String body;
        private final Long inReplyToId; // 스레드 재구성용 - 답글이 가리키는 최상위(루트) 코멘트 ID (루트 자신은 null)
        private final Long pullRequestReviewId; // 이 코멘트를 제출한 리뷰 - 단일 코멘트와 답글도 GitHub이 리뷰를 하나씩 만든다

        private final String htmlUrl;
        private final String filePath;
        private final Integer line;
        private final String side;
        private final Integer startLine;
        private final Integer originalLine;
        private final String subjectType;   // "line" | "file"
        private final String reviewState;   // 리뷰 총평(review_body)일 때만
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
        private Long id;
        private String body;
        private GhUser user;
        @JsonProperty("in_reply_to_id")
        private Long inReplyToId;

        // 아래는 리뷰 단위 분석에서 CommentEvent를 만들 때 사용
        // 리뷰별 코멘트 API(/reviews/{id}/comments)는 line/side/subject_type을 주지 않아, PR 전체 목록에서 리뷰 id로 골라낸다
        @JsonProperty("pull_request_review_id")
        private Long pullRequestReviewId;
        @JsonProperty("html_url")
        private String htmlUrl;
        private String path;
        private Integer line;
        private String side;
        @JsonProperty("start_line")
        private Integer startLine;
        @JsonProperty("original_line")
        private Integer originalLine;
        @JsonProperty("subject_type")
        private String subjectType;
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
        private String title;
        private String body;
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
    public static class GhPullFile {
        private String filename;
        private String status;
        private String patch;
        private Integer additions;
        private Integer deletions;
    }
}
