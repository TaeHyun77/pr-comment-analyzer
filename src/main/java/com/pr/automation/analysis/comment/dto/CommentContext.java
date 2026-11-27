package com.pr.automation.analysis.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class CommentContext {
    private final long commentId; // 묶음 분석에서 결과를 코멘트에 되짚는 키
    private final String eventType; // review_comment(코드 인라인) 또는 issue_comment(PR 일반 코멘트)
    private final String repoFullName;

    private final int prNumber;
    private final String prTitle;
    private final String prBody;
    private final String prAuthor; // PR 작성자 로그인 - 답변 초안은 이 사람 입장에서 쓴다

    private final String headSha; // 코멘트 시점 PR head 커밋 SHA, 체크아웃 기준 커밋(issue_comment면 null)
    private final String filePath; // 코멘트가 달린 파일 경로
    private final Integer line; // 코멘트가 달린 줄 번호 (outdated 코멘트면 null)
    private final String side; // RIGHT=변경 후(head) 기준, LEFT=변경 전(base) 기준
    private final Integer startLine; // 멀티라인 코멘트의 시작 라인
    private final Integer originalLine; // 코멘트 작성 시점 커밋 기준 라인
    private final boolean fileLevel; // 특정 라인이 아닌 파일 전체를 대상으로 달린 코멘트 (이때 line 값은 의미가 없음)
    private final String reviewState; // review_body인 경우 리뷰 상태 — 같은 문장이라도 승인/변경요청에 따라 무게가 다르므로 함께 전달
    private final String filePatch; // 코멘트가 달린 파일의 전체 변경 unified diff (미확보 시 null)
    private final List<String> parentComments;
    private final String commentAuthor; // 분석 대상 코멘트 작성자 - PR 작성자 본인의 코멘트인지 LLM이 구분하도록 전달
    private final String commentBody; // 리뷰어가 실제로 남긴 코멘트 내용
}
