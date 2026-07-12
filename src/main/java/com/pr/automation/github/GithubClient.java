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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * GitHub REST API 호출 - 토큰이 없으면 비활성 상태이며 모든 조회가 empty()를 반환
 * 조회 실패는 분석을 막지 않도록 예외를 삼키고 빈 값을 반환
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubClient {
    private final RestTemplate githubRestTemplate;
    private final GithubProperties githubProperties;

    public boolean isEnabled() {
        return StringUtils.hasText(githubProperties.getToken());
    }

    // PR 리뷰 코멘트 단건을 조회 - 답글의 부모 코멘트를 조회하기 위해 사용
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
    // 이 PR 브랜치의 최신 커밋( 변경이 다 반영된 after 상태 ) 을 조회해, 파일들을 그 기준으로 읽기 위한 것
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

    // 특정 커밋을 기준으로 레포의 파일 하나 내용을 읽어옴, 파일이 아닌( 디렉터리/심볼릭링크 등 ) 경로면 empty
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

    // 디렉터리 안의 파일/하위디렉터리 목록을 가져옴 ( path가 빈 문자열이면 레포 루트 )
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

    /**
     * PR의 변경 파일 목록(파일명/상태/patch)을 가져옴, 첫 페이지(최대 100개)만 조회
     * 조회 실패는 분석을 막지 않도록 예외를 삼키고 빈 리스트를 반환
     */
    public List<ChangedFile> fetchPullFiles(String repoFullName, int prNumber) {
        if (!isEnabled()) return Collections.emptyList();

        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            return Collections.emptyList();
        }
        try {
            GhPullFile[] files = githubRestTemplate.getForObject(
                    "/repos/{owner}/{repo}/pulls/{number}/files?per_page=100",
                    GhPullFile[].class,
                    parts[0], parts[1], prNumber);
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
        } catch (Exception e) {
            log.warn("GitHub PR 변경 파일 조회 실패: {} #{}", repoFullName, prNumber, e);
            return Collections.emptyList();
        }
    }

    /**
     * PR(=issue)에 코멘트를 작성 -> 리뷰 결과를 PR 코멘트로 올리는 용도
     * 게시 실패는 호출자가 인지해야 하므로(폴백/재시도) 예외를 던집니다, 토큰에 쓰기 권한(issues/PR write)이 없으면 실패
     */
    public void createIssueComment(String repoFullName, int prNumber, String body) {
        if (!isEnabled()) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.GITHUB_API_ERROR, "GitHub 토큰 미설정으로 코멘트 게시 불가");
        }

        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.GITHUB_API_ERROR, "레포 식별 불가: " + repoFullName);
        }
        try {
            githubRestTemplate.postForObject(
                    "/repos/{owner}/{repo}/issues/{number}/comments",
                    Collections.singletonMap("body", body),
                    Void.class,
                    parts[0], parts[1], prNumber);
        } catch (RestClientException e) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.GITHUB_API_ERROR, "PR 코멘트 게시 실패: " + repoFullName + " #" + prNumber, e);
        }
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
