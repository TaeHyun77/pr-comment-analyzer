package com.pr.automation.analysis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class FunctionDef {
    private final String name;
    private final String description;
    private final Object parameters;
}
