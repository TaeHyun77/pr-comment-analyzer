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

    // 모델과 노력 수준을 명시하지 않으면 실행 호스트의 개인 설정(~/.claude/settings.json)이 결정하게 되어
    // 동작이 운영자마다 달라진다 — 반드시 앱 설정으로 고정한다
    private final String model;  // sonnet / opus / haiku 등 별칭 또는 전체 모델명
    private final String effort; // low, medium, high, xhigh, max

    // 네이티브 도구 모드에서 허용할 내장 도구 (공백 구분). 읽기 전용으로 고정한다 —
    // 체크아웃에는 gradlew와 빌드 스크립트가 들어 있어 Bash를 주면 실행될 수 있다
    private final String allowedTools;
}
