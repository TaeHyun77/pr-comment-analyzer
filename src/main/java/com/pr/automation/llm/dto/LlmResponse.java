package com.pr.automation.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

// LlmChatClient.send()의 반환값 - 모델 응답 메시지와 자원 사용량을 함께 전달
@Getter
@AllArgsConstructor
public class LlmResponse {
    private final ChatMessage message;
    private final LlmUsage usage;

    public static LlmResponse of(ChatMessage message) {
        return new LlmResponse(message, LlmUsage.zero());
    }
}
