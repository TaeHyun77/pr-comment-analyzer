package com.pr.automation.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

import java.util.List;

/**
 * - token: PR/파일 조회용 PAT, 비어 있으면 웹훅 payload 데이터만으로 동작
 * - login: 내 GitHub 로그인, 이 사용자가 author인 PR의 코멘트만 분석
 * - webhookSecret: GitHub 웹훅 등록 시 입력한 secret, X-Hub-Signature-256 검증에 사용
 * - repos: 웹훅 복구 대상 레포 풀네임 목록 (owner/repo). 비어 있으면 복구 비활성
 * - webhookRecovery: 웹훅 복구 동작 설정. null이면 비활성
 */
@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("github")
public class GithubProperties {
    private final String token;
    private final String login;
    private final String webhookSecret;
    private final List<String> repos;
    private final WebhookRecovery webhookRecovery;

    @Getter
    @AllArgsConstructor
    public static class WebhookRecovery {
        private final boolean enabled;
        private final int lookbackHours;
        private final String stateFile;
        // 운영 중 사각지대 자동 회복용 스케줄러 설정
        private final boolean schedulerEnabled;
        private final long schedulerIntervalMs;
        private final int schedulerLookbackHours; // 현재 시각으로부터 몇 시간 전까지의 deliveries를 복구 대상으로 볼지
    }
}
