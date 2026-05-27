package com.pr.automation.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

// 부팅 직후 필수 환경변수가 비어 있지 않은지 검증
// 누락 시 IllegalStateException으로 즉시 종료시켜 디버깅 비용을 줄임
@Slf4j
@Component
@RequiredArgsConstructor
public class RequiredEnvValidator implements ApplicationRunner {

    private final GithubProperties githubProperties;
    private final GroqProperties groqProperties;
    private final SlackProperties slackProperties;

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = new ArrayList<>();

        if (!StringUtils.hasText(githubProperties.getToken())) {
            missing.add("GITHUB_TOKEN");
        }
        if (!StringUtils.hasText(githubProperties.getLogin())) {
            missing.add("GITHUB_LOGIN");
        }
        if (!StringUtils.hasText(githubProperties.getWebhookSecret())) {
            missing.add("GITHUB_WEBHOOK_SECRET");
        }
        if (!StringUtils.hasText(groqProperties.getApiKey())) {
            missing.add("GROQ_API_KEY");
        }
        if (slackProperties.isEnabled()
                && !StringUtils.hasText(slackProperties.getWebhookUrl())) {
            missing.add("SLACK_WEBHOOK_URL");
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "필수 환경변수 누락: " + String.join(", ", missing)
                            + ". .env 파일 또는 OS 환경변수를 확인하세요."
                            + " (참고: .env.example)");
        }
        log.info("필수 환경변수 검증 통과");
    }
}
