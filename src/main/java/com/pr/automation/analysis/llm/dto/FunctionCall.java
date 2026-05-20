package com.pr.automation.analysis.llm.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FunctionCall {
    private String name;
    private String arguments;
}
