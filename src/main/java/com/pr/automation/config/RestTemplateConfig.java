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
    // 외부 API 무한 대기 방지용 타임아웃. LLM read 타임아웃은 모델별 지연 편차가 커 env(LLM_READ_TIMEOUT_MS)로 분리
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    private final GroqProperties groqProperties;
    private final GithubProperties githubProperties;

    @Bean
    public RestTemplate groqRestTemplate(RestTemplateBuilder builder) {
        RestTemplate rt = builder
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setReadTimeout(Duration.ofMillis(groqProperties.getReadTimeoutMs()))
                .build();
        rt.setUriTemplateHandler(new DefaultUriBuilderFactory(groqProperties.getBaseUrl()));
        rt.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + groqProperties.getApiKey());
            request.getHeaders().set(HttpHeaders.USER_AGENT, "pr-comment-analyzer/1.0");
            return execution.execute(request, body);
        });
        return rt;
    }

    @Bean
    public RestTemplate githubRestTemplate(RestTemplateBuilder builder) {
        RestTemplate rt = builder
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setReadTimeout(DEFAULT_READ_TIMEOUT)
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
                .setReadTimeout(DEFAULT_READ_TIMEOUT)
                .build();
    }
}
