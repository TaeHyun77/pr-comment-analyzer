package com.pr.automation.analysis.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

// API에 보내는 요청 바디
@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {
    private final String model;
    private final List<ChatMessage> messages;
    private final double temperature;

    @JsonProperty("max_tokens")
    private final Integer maxTokens;

    private final List<Tool> tools;

    @JsonProperty("tool_choice")
    private final Object toolChoice;
}
