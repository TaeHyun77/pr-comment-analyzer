package com.pr.automation.analysis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

// OpenAI 호환 tool 정의 (type=function 고정)
@Getter
@AllArgsConstructor
public class Tool {
    private final String type;
    private final FunctionDef function;
}
