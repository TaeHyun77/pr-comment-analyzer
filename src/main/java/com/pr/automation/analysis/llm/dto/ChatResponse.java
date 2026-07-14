package com.pr.automation.analysis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// API 응답 바디(choices 리스트)를 담는 최상위 DTO
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private List<Choice> choices;
}
