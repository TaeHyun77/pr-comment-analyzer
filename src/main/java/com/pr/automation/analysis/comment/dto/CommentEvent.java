package com.pr.automation.analysis.comment.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

// 웹훅 페이로드에서 추출한 원본 이벤트 정보
// CommentContext의 소스가 되는 raw 데이터 (딜리버리 ID, 커밋 SHA, 코멘트 ID/작성자/URL, 답글 부모 ID 등)
@Getter
@Builder
@AllArgsConstructor
public class CommentEvent {
    public static final String TYPE_REVIEW_COMMENT = "review_comment";
    public static final String TYPE_ISSUE_COMMENT = "issue_comment";
    // 리뷰 제출 이벤트 - 총평과 함께 제출된 인라인 코멘트를 묶어 분석하는 단위. 인라인 코멘트만 있는 리뷰면 commentBody가 비어 있다
    public static final String TYPE_REVIEW_BODY = "review_body";
    private static final String SUBJECT_TYPE_FILE = "file";

    private final String eventType; // "review_comment" / "issue_comment" / "review_body"
    private final String repoFullName;

    private final int prNumber;
    private final String prTitle;
    private final String prBody;
    private final String prAuthor; // PR 작성자 로그인 - 스레드에서 누가 작성자 본인인지 LLM에 알려 답변 초안의 입장을 정하기 위함

    private final String headSha; // 코멘트 발생 시점 PR head 커밋 SHA (issue_comment면 null)

    private final long commentId;
    private final String commentBody;
    private final String commentAuthor;
    private final String commentHtmlUrl;

    private final String filePath;
    private final Integer line; // 코멘트 라인 (outdated 코멘트면 null)
    private final String side; // RIGHT=변경 후(head) 기준, LEFT=변경 전(base) 기준
    private final Integer startLine; // 멀티라인 코멘트의 시작 라인
    private final Integer originalLine; // 코멘트 작성 시점 커밋 기준 라인
    private final Long inReplyToId; // 답글인 경우 최상위 부모 코멘트 ID
    private final String subjectType; // "line" 또는 "file" (issue_comment면 null)
    private final String reviewState; // review_body인 경우 리뷰 상태(approved/changes_requested/commented)

    // review_comment인지 여부를 판별
    @JsonIgnore
    public boolean isReviewComment() {
        return TYPE_REVIEW_COMMENT.equals(eventType);
    }

    // 리뷰 제출 시 남긴 총평 본문인지 판별
    @JsonIgnore
    public boolean isReviewBody() {
        return TYPE_REVIEW_BODY.equals(eventType);
    }

    // 특정 라인이 아니라 파일 전체를 대상으로 달린 코멘트인지 판별.
    // GitHub이 이 경우에도 line을 1로 채워 보내므로, line 값으로는 구분할 수 없어 subjectType으로만 판정한다
    @JsonIgnore
    public boolean isFileLevel() {
        return SUBJECT_TYPE_FILE.equalsIgnoreCase(subjectType);
    }
}
