package com.pr.automation.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class RestTemplateConfig {
    // 외부 API 무한 대기 방지용 connect 타임아웃(공통). read 타임아웃은 대상별 지연 편차가 커 각 env로 분리
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final LlmProperties llmProperties;
    private final GithubProperties githubProperties;
    private final SlackProperties slackProperties;

    @Bean
    public RestTemplate llmRestTemplate(RestTemplateBuilder builder) {
        RestTemplate rt = builder
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setReadTimeout(Duration.ofMillis(llmProperties.getReadTimeoutMs()))
                .build();
        rt.setUriTemplateHandler(new DefaultUriBuilderFactory(llmProperties.getBaseUrl()));
        rt.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.getApiKey());
            request.getHeaders().set(HttpHeaders.USER_AGENT, "pr-comment-analyzer/1.0");
            return execution.execute(request, body);
        });
        return rt;
    }

    @Bean
    public RestTemplate githubRestTemplate(RestTemplateBuilder builder) {
        RestTemplate rt = builder
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setReadTimeout(Duration.ofMillis(githubProperties.getReadTimeoutMs()))
                .build();
        rt.setUriTemplateHandler(new DefaultUriBuilderFactory("https://api.github.com"));
        rt.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.ACCEPT, "application/vnd.github+json");
            request.getHeaders().set("X-GitHub-Api-Version", "2022-11-28");
            if (StringUtils.hasText(githubProperties.getToken())) {
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + githubProperties.getToken());
            }
            return execution.execute(request, body);
        });
        return rt;
    }

    @Bean
    public RestTemplate slackRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setReadTimeout(Duration.ofMillis(slackProperties.getReadTimeoutMs()))
                .build();
    }
}
