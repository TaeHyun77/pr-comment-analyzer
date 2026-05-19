package com.pr.automation.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

/**
 * - token: PR/파일 조회용 PAT, 비어 있으면 웹훅 payload 데이터만으로 동작
 * - login: 내 GitHub 로그인, 이 사용자가 author인 PR의 코멘트만 분석
 * - webhookSecret: GitHub 웹훅 등록 시 입력한 secret, X-Hub-Signature-256 검증에 사용
 */
@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("github")
public class GithubProperties {
    private final String token;
    private final String login;
    private final String webhookSecret;
}
