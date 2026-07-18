package com.pr.automation.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

// LLM 설정
@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("llm")
public class LlmProperties {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final int readTimeoutMs; // LLM API 응답 대기 시간(ms). 모델 교체 시 지연 편차 대응용
    private final int maxAttempts;   // 일시적 오류(429/5xx/네트워크) 재시도 횟수
}
