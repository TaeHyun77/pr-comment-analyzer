package com.pr.automation.analysis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Tool {
    private final String type;
    private final FunctionDef function;
}
