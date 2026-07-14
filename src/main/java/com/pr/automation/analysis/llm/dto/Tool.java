package com.pr.automation.analysis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 하나의 도구를 표현
@Getter
@AllArgsConstructor
public class Tool {
    private final String type;
    private final FunctionDef function;
}
