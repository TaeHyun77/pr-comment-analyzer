package com.pr.automation.analysis.llm;

import com.pr.automation.analysis.llm.dto.ChatMessage;
import com.pr.automation.analysis.llm.dto.ChatResponse;
import com.pr.automation.analysis.llm.dto.Choice;
import com.pr.automation.analysis.llm.dto.Tool;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.GroqProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재시도/예외 분류 동작 검증.
 * 실제 sleep 시간을 없애기 위해 sleepWithFullJitter를 no-op으로 오버라이드한 서브클래스 사용.
 */
class GroqChatClientRetryTest {

    private static final List<ChatMessage> MESSAGES = Collections.singletonList(ChatMessage.user("hi"));
    private static final List<Tool> TOOLS = Collections.emptyList();
    private static final Object CHOICE = "auto";

    private RestTemplate restTemplate;
    private TestGroqChatClient client;
    private AtomicInteger sleepCount;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        sleepCount = new AtomicInteger();
        GroqProperties props = new GroqProperties("key", "http://localhost", "model", 2048, 0.3, 60000, 3);
        client = new TestGroqChatClient(restTemplate, props, sleepCount);
    }

    @Test
    void 정상응답_1회로_반환() {
        when(restTemplate.postForObject(anyString(), any(), eq(ChatResponse.class)))
                .thenReturn(responseOf("hello"));

        ChatMessage result = client.send(MESSAGES, TOOLS, CHOICE);

        assertThat(result.getContent()).isEqualTo("hello");
        verify(restTemplate, times(1)).postForObject(anyString(), any(), eq(ChatResponse.class));
        assertThat(sleepCount.get()).isZero();
    }

    @Test
    void rateLimit_429_두번_후_성공() {
        when(restTemplate.postForObject(anyString(), any(), eq(ChatResponse.class)))
                .thenThrow(clientError(HttpStatus.TOO_MANY_REQUESTS))
                .thenThrow(clientError(HttpStatus.TOO_MANY_REQUESTS))
                .thenReturn(responseOf("recovered"));

        ChatMessage result = client.send(MESSAGES, TOOLS, CHOICE);

        assertThat(result.getContent()).isEqualTo("recovered");
        verify(restTemplate, times(3)).postForObject(anyString(), any(), eq(ChatResponse.class));
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void serverError_503_세번_모두_실패시_재시도횟수초과() {
        when(restTemplate.postForObject(anyString(), any(), eq(ChatResponse.class)))
                .thenThrow(serverError(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.send(MESSAGES, TOOLS, CHOICE))
                .isInstanceOf(AutomationException.class)
                .hasMessageContaining("재시도 횟수 초과")
                .hasMessageContaining("503")
                .extracting("errorCode").isEqualTo(ErrorCode.AI_API_ERROR);

        verify(restTemplate, times(3)).postForObject(anyString(), any(), eq(ChatResponse.class));
        assertThat(sleepCount.get()).isEqualTo(2); // 마지막 시도 후엔 sleep 없이 즉시 종료
    }

    @Test
    void clientError_401은_재시도없이_즉시실패() {
        HttpClientErrorException e = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", null,
                "{\"error\":{\"message\":\"Invalid API Key\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(restTemplate.postForObject(anyString(), any(), eq(ChatResponse.class)))
                .thenThrow(e);

        assertThatThrownBy(() -> client.send(MESSAGES, TOOLS, CHOICE))
                .isInstanceOf(AutomationException.class)
                .hasMessageContaining("Groq API 클라이언트 오류")
                .hasMessageContaining("401");

        verify(restTemplate, times(1)).postForObject(anyString(), any(), eq(ChatResponse.class));
        assertThat(sleepCount.get()).isZero();
    }

    @Test
    void networkError_세번_모두_실패시_재시도횟수초과() {
        when(restTemplate.postForObject(anyString(), any(), eq(ChatResponse.class)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        assertThatThrownBy(() -> client.send(MESSAGES, TOOLS, CHOICE))
                .isInstanceOf(AutomationException.class)
                .hasMessageContaining("재시도 횟수 초과")
                .hasMessageContaining("네트워크");

        verify(restTemplate, times(3)).postForObject(anyString(), any(), eq(ChatResponse.class));
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void restClientException은_catchAll로_봉쇄되어_재시도없이_즉시실패() {
        // 응답 매핑 실패 등을 시뮬레이션 — HttpStatusCodeException/ResourceAccessException이 아닌 RestClientException
        when(restTemplate.postForObject(anyString(), any(), eq(ChatResponse.class)))
                .thenThrow(new RestClientException("body parse error"));

        assertThatThrownBy(() -> client.send(MESSAGES, TOOLS, CHOICE))
                .isInstanceOf(AutomationException.class)
                .hasMessageContaining("처리되지 않은 예외")
                .hasMessageContaining("body parse error");

        verify(restTemplate, times(1)).postForObject(anyString(), any(), eq(ChatResponse.class));
        assertThat(sleepCount.get()).isZero();
    }

    @Test
    void choices가_비어있으면_빈응답_예외() {
        when(restTemplate.postForObject(anyString(), any(), eq(ChatResponse.class)))
                .thenReturn(new ChatResponse(new ArrayList<>()));

        assertThatThrownBy(() -> client.send(MESSAGES, TOOLS, CHOICE))
                .isInstanceOf(AutomationException.class)
                .hasMessageContaining("AI API의 빈 응답");

        verify(restTemplate, times(1)).postForObject(anyString(), any(), eq(ChatResponse.class));
    }

    // --- 헬퍼 ---

    private static ChatResponse responseOf(String content) {
        ChatMessage m = new ChatMessage("assistant", content);
        return new ChatResponse(Collections.singletonList(new Choice(m, "stop")));
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static HttpServerErrorException serverError(HttpStatus status) {
        return HttpServerErrorException.create(status, status.getReasonPhrase(), null,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /**
     * sleep을 no-op으로 만들어 테스트 시간을 단축. 실제 호출 횟수만 카운트.
     */
    private static class TestGroqChatClient extends GroqChatClient {
        private final AtomicInteger counter;

        TestGroqChatClient(RestTemplate rt, GroqProperties props, AtomicInteger counter) {
            super(rt, props);
            this.counter = counter;
        }

        @Override
        protected void sleepWithFullJitter(long maxMillis) {
            counter.incrementAndGet();
            // no sleep
        }
    }
}
