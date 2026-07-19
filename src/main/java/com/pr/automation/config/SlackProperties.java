package com.pr.automation.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

// Slack Incoming Webhook 설정, enabled=false면 전송을 생략하고 분석 결과를 로그에만 남김
@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("slack")
public class SlackProperties {
    private final boolean enabled;
    private final String webhookUrl;
    private final int maxAttempts;    // 전송 일시 오류 재시도 횟수 (LLM_MAX_ATTEMPTS와 동일 클래스)
    private final int readTimeoutMs;  // Slack API 응답 대기 시간(ms)
}
