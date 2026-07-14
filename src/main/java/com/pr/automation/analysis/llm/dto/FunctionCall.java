package com.pr.automation.analysis.llm.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 도구 호출 시 실제 호출된 함수 정보
@Getter
@Setter
@NoArgsConstructor
public class FunctionCall {
    private String name;
    private String arguments;
}
