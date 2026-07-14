package com.pr.automation.analysis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 도구 스키마 정의
@Getter
@AllArgsConstructor
public class FunctionDef {
    private final String name;
    private final String description;
    private final Object parameters;
}
