package com.pr.automation.slack;

import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.SlackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slack 전송 재시도 동작 검증 — LlmChatClientRetryTest와 같은 패턴
 * (sleep을 no-op으로 오버라이드해 테스트 시간 단축)
 */
class SlackNotifierRetryTest {

    private static final CommentEvent EVENT = CommentEvent.builder()
            .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
            .repoFullName("me/repo")
            .prNumber(7)
            .commentId(555L)
            .commentBody("코멘트")
            .build();
    private static final AnalysisResult RESULT =
            new AnalysisResult("요약", "현재", "제안", "현 구현 유지 권장", "근거", "답변");

    private RestTemplate restTemplate;
    private TestSlackNotifier notifier;
    private AtomicInteger sleepCount;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        sleepCount = new AtomicInteger();
        notifier = new TestSlackNotifier(restTemplate, new SlackProperties(true, "http://slack.local/hook"), sleepCount);
    }

    @Test
    void 정상전송은_1회로_끝난다() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn("ok");

        assertThatCode(() -> notifier.send(EVENT, RESULT)).doesNotThrowAnyException();

        verify(restTemplate, times(1)).postForObject(anyString(), any(), eq(String.class));
        assertThat(sleepCount.get()).isZero();
    }

    @Test
    void 일시오류는_재시도후_성공하면_예외없이_끝난다() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(serverError(HttpStatus.SERVICE_UNAVAILABLE))
                .thenThrow(clientError(HttpStatus.TOO_MANY_REQUESTS))
                .thenReturn("ok");

        assertThatCode(() -> notifier.send(EVENT, RESULT)).doesNotThrowAnyException();

        verify(restTemplate, times(3)).postForObject(anyString(), any(), eq(String.class));
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void 일시오류가_계속되면_재시도_소진후_예외를_던진다() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(serverError(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> notifier.send(EVENT, RESULT))
                .isInstanceOf(AutomationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SLACK_API_ERROR);

        verify(restTemplate, times(3)).postForObject(anyString(), any(), eq(String.class));
        // 마지막 시도 후엔 sleep 없이 즉시 종료
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void 영구오류_4xx는_재시도없이_즉시_예외를_던진다() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(clientError(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> notifier.send(EVENT, RESULT))
                .isInstanceOf(AutomationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SLACK_API_ERROR);

        verify(restTemplate, times(1)).postForObject(anyString(), any(), eq(String.class));
        assertThat(sleepCount.get()).isZero();
    }

    @Test
    void 네트워크오류_모두_실패시_예외를_던진다() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        assertThatThrownBy(() -> notifier.send(EVENT, RESULT))
                .isInstanceOf(AutomationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SLACK_API_ERROR);

        verify(restTemplate, times(3)).postForObject(anyString(), any(), eq(String.class));
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void 비활성화면_전송하지_않는다() {
        SlackNotifier disabled = new SlackNotifier(restTemplate, new SlackProperties(false, "http://slack.local/hook"));

        disabled.send(EVENT, RESULT);

        verify(restTemplate, times(0)).postForObject(anyString(), any(), eq(String.class));
    }

    // --- 헬퍼 ---

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static HttpServerErrorException serverError(HttpStatus status) {
        return HttpServerErrorException.create(status, status.getReasonPhrase(), null,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /**
     * sleep을 no-op으로 만들어 테스트 시간을 단축. 호출 횟수만 카운트.
     */
    private static class TestSlackNotifier extends SlackNotifier {
        private final AtomicInteger counter;

        TestSlackNotifier(RestTemplate rt, SlackProperties props, AtomicInteger counter) {
            super(rt, props);
            this.counter = counter;
        }

        @Override
        protected boolean sleepWithFullJitter(long maxMillis) {
            counter.incrementAndGet();
            return true;
        }
    }
}
