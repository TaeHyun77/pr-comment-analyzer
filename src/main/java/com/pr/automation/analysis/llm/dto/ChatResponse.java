package com.pr.automation.analysis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// LLM에게 받는 응답
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private List<Choice> choices;
}
