package com.pr.automation.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

@Configuration
@RequiredArgsConstructor
public class RestTemplateConfig {

    private final GroqProperties groqProperties;
    private final GithubProperties githubProperties;

    @Bean
    public RestTemplate groqRestTemplate() {
        RestTemplate rt = new RestTemplate();
        rt.setUriTemplateHandler(new DefaultUriBuilderFactory(groqProperties.getBaseUrl()));
        rt.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + groqProperties.getApiKey());
            return execution.execute(request, body);
        });
        return rt;
    }

    @Bean
    public RestTemplate githubRestTemplate() {
        RestTemplate rt = new RestTemplate();
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
    public RestTemplate slackRestTemplate() {
        return new RestTemplate();
    }
}
