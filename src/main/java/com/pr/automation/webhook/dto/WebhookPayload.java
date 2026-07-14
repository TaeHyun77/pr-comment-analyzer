package com.pr.automation.webhook.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

// GitHub 웹훅이 보내는 JSON 페이로드 원본을 매핑하는 DTO
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WebhookPayload {

    private String action; // 이벤트의 동작. 코멘트 생성이면 "created"

    private Comment comment;

    @JsonProperty("pull_request")
    private PullRequest pullRequest;

    private Issue issue;

    private Repository repository;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Comment {
        private long id;
        private String body;
        private String path;

        @JsonProperty("diff_hunk")
        private String diffHunk;
        private Integer line;

        // 코멘트가 달린 diff 쪽: RIGHT=변경 후(head), LEFT=변경 전(base, 삭제된 라인)
        private String side;

        @JsonProperty("start_line")
        private Integer startLine; // 멀티라인 코멘트의 시작 라인 (단일 라인이면 null)

        @JsonProperty("original_line")
        private Integer originalLine;

        @JsonProperty("html_url")
        private String htmlUrl;
        private User user;

        @JsonProperty("in_reply_to_id")
        private Long inReplyToId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PullRequest {
        private int number;
        private String title;
        private String body;
        private User user;
        private Head head;

        @JsonProperty("html_url")
        private String htmlUrl;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Head {
        private String sha;
        private String ref;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Issue {
        private int number;
        private String title;
        private String body;
        private User user;

        @JsonProperty("pull_request")
        private Map<String, Object> pullRequest;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Repository {
        @JsonProperty("full_name")
        private String fullName;

        private String name;
        private User owner;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        private String login;
        private String type;
    }
}
