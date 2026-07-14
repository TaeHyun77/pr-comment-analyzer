package com.pr.automation.analysis.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// LLM 응답에 포함된 도구 호출 하나를 담는 DTO
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolCall {
    private String id;
    private String type;
    private FunctionCall function;
}
