package com.pr.automation.llm.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// LLM 응답을 담는 DTO. 대화 이력을 앱이 관리하던 시절의 도구 필드는 CLI가 루프를 맡으면서 필요 없어졌다
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {
    public static final String ROLE_ASSISTANT = "assistant";

    private String role;
    private String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content == null ? "" : content);
    }
}
