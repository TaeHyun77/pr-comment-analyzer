package com.pr.automation.github;

import com.pr.automation.config.GithubProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * GitHub REST API 호출. 토큰이 없으면 비활성 상태이며 모든 조회가 empty()를 반환함.
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

    // PR 리뷰 코멘트 단건 조회 (답글의 부모 코멘트 등)
    public Optional<FetchedComment> fetchReviewComment(String repoFullName, long commentId) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String[] parts = repoFullName.split("/", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            GhComment comment = githubRestTemplate.getForObject(
                    "/repos/{owner}/{repo}/pulls/comments/{id}",
                    GhComment.class,
                    parts[0], parts[1], commentId);
            if (comment == null) {
                return Optional.empty();
            }
            String author = comment.getUser() != null && comment.getUser().getLogin() != null
                    ? comment.getUser().getLogin()
                    : "";
            String body = comment.getBody() != null ? comment.getBody() : "";
            return Optional.of(FetchedComment.builder()
                    .id(commentId)
                    .author(author)
                    .body(body)
                    .build());
        } catch (Exception e) {
            log.warn("GitHub 코멘트 조회 실패: {} comment={}", repoFullName, commentId, e);
            return Optional.empty();
        }
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class FetchedComment {
        private final long id;
        private final String author;
        private final String body;
    }

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
}
