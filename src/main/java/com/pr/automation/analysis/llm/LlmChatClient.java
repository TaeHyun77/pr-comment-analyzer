package com.pr.automation.analysis.llm;

import com.pr.automation.analysis.llm.dto.ChatMessage;
import com.pr.automation.analysis.llm.dto.ChatRequest;
import com.pr.automation.analysis.llm.dto.ChatResponse;
import com.pr.automation.analysis.llm.dto.Tool;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.LlmProperties;
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
 * LLM API와 통신하는 순수 전송 계층
 * 프롬프트 구성, 대화 루프 진행, 도구 스키마 결정 등은 이 클래스의 책임이 아니며, 단지 보내는 역할만 담당합니다.
 *
 * 재시도 담당
 * 일시적 실패 (429, 5xx, 네트워크 순단)는 MAX_ATTEMPTS 회 재시도 하도록 함 , 4xx(429 제외)는 응답 body 로깅 후 즉시 실패
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmChatClient {
    private static final long INITIAL_BACKOFF_MS = 1000L; // 재시도 백오프의 시작값
    private static final int BODY_LOG_LIMIT = 500; // 에러 로그에 남길 응답 바디의 최대 길이

    private final RestTemplate llmRestTemplate;
    private final LlmProperties llmProperties;

    // 메인이 되는 진입점
    public ChatMessage send(List<ChatMessage> messages, List<Tool> tools, Object toolChoice) {
        ChatRequest request = new ChatRequest(
                llmProperties.getModel(),
                messages,
                llmProperties.getTemperature(),
                llmProperties.getMaxTokens(),
                tools,
                toolChoice
        );

        int attempt = 0;
        long backoffMillis = INITIAL_BACKOFF_MS;
        int maxAttempts = llmProperties.getMaxAttempts();

        while (attempt < maxAttempts) {
            try {
                ChatResponse response = llmRestTemplate.postForObject("/chat/completions", request, ChatResponse.class);
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
                log.error("LLM API 클라이언트 오류 {} body={}", status, body);
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "LLM API 클라이언트 오류: " + status, e);

            } catch (ResourceAccessException e) {
                // 3) 네트워크 순단 / 타임아웃 → 재시도
                backoffMillis = handleRetry(++attempt, backoffMillis, "네트워크 연결 오류(" + e.getMessage() + ")", e);

            } catch (RestClientException e) {
                // 4) catch-all - 응답 매핑 실패, UnknownContentType, 향후 새 하위 예외 등
                // 예외 누수를 봉쇄해 호출자(@Async 경계 포함)가 항상 AutomationException만 보게 함
                log.error("LLM API 호출 중 처리되지 않은 예외: {}", e.getMessage(), e);
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "LLM API 호출 중 처리되지 않은 예외: " + e.getMessage(), e);
            }
        }

        // 도달 불가 - while 안의 모든 경로가 return 또는 throw로 종료됨
        throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "알 수 없는 오류로 LLM 호출 실패 (루프 이탈)");
    }

    // 재시도 관련 로직을 한 곳에 모든 헬터 메서드
    // 지수 백오프( 매 실패마다 대기 시간 2배 )를 구현하며, 반환값을 send()의 backoffMillis 변수에 재대입하는 방식으로 상태를 이어갑니다.
    private long handleRetry(int attempt, long currentBackoff, String causeMsg, Exception e) {
        int maxAttempts = llmProperties.getMaxAttempts();
        if (attempt >= maxAttempts) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "LLM API 재시도 횟수 초과: " + causeMsg, e);
        }
        log.warn("LLM {}, 최대 {}ms 내외 대기 후 재시도 ({}/{})", causeMsg, currentBackoff, attempt, maxAttempts);
        sleepWithFullJitter(currentBackoff);
        return currentBackoff * 2;
    }

    // 재시도 시간 계산
    protected void sleepWithFullJitter(long maxMillis) {
        try {
            long jitter = ThreadLocalRandom.current().nextLong(maxMillis + 1);
            Thread.sleep(jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AutomationException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AI_API_ERROR, "재시도 대기 중 인터럽트 발생");
        }
    }

    // 응답에서 message 추출
    private ChatMessage validateAndGetMessage(ChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty() || response.getChoices().get(0).getMessage() == null) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "AI API의 빈 응답");
        }
        return response.getChoices().get(0).getMessage();
    }

    // 4xx 에러 로깅 시 응답 바디를 500자로 잘라 로그 가독성을 지키는 유틸
    private static String abbreviate(String s, int max) {
        if (s == null || s.isEmpty()) return "(빈 응답)";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
