package com.pr.automation.analysis.llm;

import com.pr.automation.analysis.llm.dto.ChatMessage;
import com.pr.automation.analysis.llm.dto.ChatRequest;
import com.pr.automation.analysis.llm.dto.ChatResponse;
import com.pr.automation.analysis.llm.dto.Tool;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.GroqProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 에이전트가 루프/프롬프트/도구 스키마는 알지 못함
 * 호출자가 messages/tools/tool_choice를 모두 결정해서 전달
 *
 * 일시적 실패(429, 5xx, 네트워크 순단)는 MAX_ATTEMPTS 회 재시도 하도록 함
 * → 4xx(429 제외)는 응답 body 로깅 후 즉시 실패, 그 외 RestClientException은 catch-all로 봉쇄
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroqChatClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000L;
    private static final int BODY_LOG_LIMIT = 500;

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

        int attempt = 0;
        long backoffMillis = INITIAL_BACKOFF_MS;

        while (attempt < MAX_ATTEMPTS) {
            try {
                ChatResponse response = groqRestTemplate.postForObject("/chat/completions", request, ChatResponse.class);
                return validateAndGetMessage(response);

            } catch (HttpStatusCodeException e) {
                HttpStatus status = e.getStatusCode();

                // 1) 429 / 5xx → 일시적 오류, 재시도
                if (status == HttpStatus.TOO_MANY_REQUESTS || status.is5xxServerError()) {
                    backoffMillis = handleRetry(++attempt, backoffMillis, "API 일시적 오류(" + status + ")", e);
                    continue;
                }

                // 2) 그 외 4xx → 영구 오류, 즉시 실패 + body 로깅
                String body = abbreviate(e.getResponseBodyAsString(), BODY_LOG_LIMIT);
                log.error("Groq API 클라이언트 오류 {} body={}", status, body);
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "Groq API 클라이언트 오류: " + status, e);

            } catch (ResourceAccessException e) {
                // 3) 네트워크 순단 / 타임아웃 → 재시도
                backoffMillis = handleRetry(++attempt, backoffMillis, "네트워크 연결 오류(" + e.getMessage() + ")", e);

            } catch (RestClientException e) {
                // 4) catch-all - 응답 매핑 실패, UnknownContentType, 향후 새 하위 예외 등.
                //    예외 누수를 봉쇄해 호출자(@Async 경계 포함)가 항상 AutomationException만 보게 함.
                log.error("Groq API 호출 중 처리되지 않은 예외: {}", e.getMessage(), e);
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "Groq API 호출 중 처리되지 않은 예외: " + e.getMessage(), e);
            }
        }

        // 도달 불가 - while 안의 모든 경로가 return 또는 throw로 종료됨. 방어적 안전망.
        throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "알 수 없는 오류로 Groq 호출 실패 (루프 이탈)");
    }

    // 재시도 한계 확인 + 로깅 + sleep + 백오프 계산을 통합
    private long handleRetry(int attempt, long currentBackoff, String causeMsg, Exception e) {
        if (attempt >= MAX_ATTEMPTS) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "Groq API 재시도 횟수 초과: " + causeMsg, e);
        }
        log.warn("Groq {}, 최대 {}ms 내외 대기 후 재시도 ({}/{})", causeMsg, currentBackoff, attempt, MAX_ATTEMPTS);
        sleepWithFullJitter(currentBackoff);
        return currentBackoff * 2;
    }

    // Full Jitter: 0 ~ maxMillis 사이 무작위, 동시 호출들의 retry 타이밍을 분산하기 위함
    protected void sleepWithFullJitter(long maxMillis) {
        try {
            long jitter = ThreadLocalRandom.current().nextLong(maxMillis + 1);
            Thread.sleep(jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AutomationException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AI_API_ERROR, "재시도 대기 중 인터럽트 발생");
        }
    }

    private ChatMessage validateAndGetMessage(ChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()
                || response.getChoices().get(0).getMessage() == null) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "AI API의 빈 응답");
        }
        return response.getChoices().get(0).getMessage();
    }

    private static String abbreviate(String s, int max) {
        if (s == null || s.isEmpty()) return "(빈 응답)";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
