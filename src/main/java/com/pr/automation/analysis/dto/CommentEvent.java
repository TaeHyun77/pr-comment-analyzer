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

    private final String eventType; // "review_comment" or "issue_comment"
    private final String repoFullName;

    // 웹훅 delivery 식별자 (X-GitHub-Delivery 헤더) - 복구 ledger 매칭 키
    private final String deliveryId;

    private final int prNumber;
    private final String prTitle;
    private final String prBody;

    private final String headSha; // 코멘트 발생 시점 PR head 커밋 SHA (issue_comment면 null)

    private final long commentId;
    private final String commentBody;
    private final String commentAuthor;
    private final String commentHtmlUrl;

    private final String filePath;
    private final String diffHunk;
    private final Integer line; // 코멘트 라인 (outdated 코멘트면 null)
    private final String side; // RIGHT=변경 후(head) 기준, LEFT=변경 전(base) 기준
    private final Integer startLine; // 멀티라인 코멘트의 시작 라인
    private final Integer originalLine; // 코멘트 작성 시점 커밋 기준 라인
    private final Long inReplyToId; // 답글인 경우 최상위 부모 코멘트 ID

    public boolean isReviewComment() {
        return TYPE_REVIEW_COMMENT.equals(eventType);
    }
}
