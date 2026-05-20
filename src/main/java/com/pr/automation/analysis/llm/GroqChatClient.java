package com.pr.automation.analysis.llm;

import com.pr.automation.analysis.llm.dto.ChatMessage;
import com.pr.automation.analysis.llm.dto.ChatRequest;
import com.pr.automation.analysis.llm.dto.ChatResponse;
import com.pr.automation.analysis.llm.dto.Tool;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.GroqProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * 에이전트가 루프/프롬프트/도구 스키마는 알지 못함
 * 호출자가 messages/tools/tool_choice를 모두 결정해서 전달
 */
@Component
@RequiredArgsConstructor
public class GroqChatClient {
    private final RestTemplate groqRestTemplate;
    private final GroqProperties groqProperties;

    // 한 라운드의 응답 메시지를 반환. 응답 형태가 비정상이면 예외.
    public ChatMessage send(List<ChatMessage> messages, List<Tool> tools, Object toolChoice) {
        ChatRequest request = new ChatRequest(
                groqProperties.getModel(),
                messages,
                groqProperties.getTemperature(),
                groqProperties.getMaxTokens(),
                tools,
                toolChoice
        );

        ChatResponse response;
        try {
            response = groqRestTemplate.postForObject("/chat/completions", request, ChatResponse.class);
        } catch (RestClientException e) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, e);
        }

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty() || response.getChoices().get(0).getMessage() == null) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "AI API의 빈 응답");
        }
        return response.getChoices().get(0).getMessage();
    }
}
