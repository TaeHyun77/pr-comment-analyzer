package com.pr.automation.analysis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

// 웹훅 페이로드에서 추출한, 분석 대상이 되는 코멘트 한 건의 정보.
@Getter
@Builder
@AllArgsConstructor
public class CommentEvent {
    public static final String TYPE_REVIEW_COMMENT = "review_comment";
    public static final String TYPE_ISSUE_COMMENT = "issue_comment";

    private final String eventType; // review_comment(코드 인라인) or issue_comment(PR 일반 코멘트)
    private final String repoFullName;
    private final int prNumber;
    private final String prTitle;
    private final String prBody;
    private final String headSha; // PR head 커밋 SHA (issue_comment면 null)
    private final long commentId;
    private final String commentBody;
    private final String commentAuthor;
    private final String commentHtmlUrl;
    private final String filePath;
    private final String diffHunk;
    private final Integer line;
    private final Long inReplyToId; // 답글인 경우 부모 코멘트 ID

    public boolean isReviewComment() {
        return TYPE_REVIEW_COMMENT.equals(eventType);
    }
}
