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
    private final String eventType;
    private final String repoFullName;
    private final int prNumber;
    private final String prTitle;
    private final String prBody;
    private final String filePath;
    private final Integer line;
    private final String codeContext;
    private final List<String> parentComments;
    private final String commentBody;
}
