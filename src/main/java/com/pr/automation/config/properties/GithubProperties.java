package com.pr.automation.config.properties;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

import java.util.List;

/**
 * - token : PR/파일 조회용 PAT, 비어 있으면 웹훅 payload 데이터만으로 동작
 * - login : 내 GitHub 로그인, 이 사용자가 author인 PR의 코멘트만 분석
 * - webhookSecret : GitHub 웹훅 등록 시 입력한 secret, X-Hub-Signature-256 검증에 사용
 * - repos : 분석/리뷰 대상 레포 풀네임 목록 (owner/repo), 비어 있으면 모든 웹훅을 차단
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
    private final int fetchMaxAttempts;  // GitHub 조회 일시 오류 재시도 횟수 (LLM_MAX_ATTEMPTS와 동일 클래스)
    private final int readTimeoutMs;     // GitHub API 응답 대기 시간(ms)
}
