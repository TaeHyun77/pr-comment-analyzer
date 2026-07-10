package com.pr.automation.analysis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

// LLM에 전달하기 위해 가공한 분석 컨텍스트
@Getter
@Builder
@AllArgsConstructor
public class CommentContext {
    private final String eventType; // review_comment(코드 인라인) 또는 issue_comment(PR 일반 코멘트)
    private final String repoFullName;

    private final int prNumber;
    private final String prTitle;
    private final String prBody;

    private final String headSha; // 코멘트 시점 PR head 커밋 SHA, 자율 파일 조회의 ref(issue_comment면 null)
    private final String filePath; // 코멘트가 달린 파일 경로
    private final Integer line; // 코멘트가 달린 줄 번호 (outdated 코멘트면 null)
    private final String side; // RIGHT=변경 후(head) 기준, LEFT=변경 전(base) 기준
    private final Integer startLine; // 멀티라인 코멘트의 시작 라인
    private final Integer originalLine; // 코멘트 작성 시점 커밋 기준 라인
    private final String codeContext; // issue_comment인 경우 null, review_comment인 경우 코멘트가 작성된 코드의 주변 문맥
    private final String filePatch; // 코멘트가 달린 파일의 전체 변경 unified diff (미확보 시 null)
    private final List<String> parentComments;
    private final String commentBody; // 리뷰어가 실제로 남긴 코멘트 내용
}
