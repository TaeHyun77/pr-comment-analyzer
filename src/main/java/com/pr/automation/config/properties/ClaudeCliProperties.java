package com.pr.automation.config.properties;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

// claude -p 서브프로세스 실행 설정
@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("claude-cli")
public class ClaudeCliProperties {
    private final int maxAttempts; // 일시적 오류 시 최대 재시도 횟수

    private final String model;  // sonnet / opus / haiku 등 별칭 또는 전체 모델명
    private final String effort; // low, medium, high, xhigh, max

    // 네이티브 도구 모드에서 허용할 내장 도구 (공백 구분). 읽기 전용으로 고정
    private final String allowedTools;

    // claude 프로세스 1회 실행의 최대 대기 시간(초)
    private final long timeoutSeconds;
}
