package com.pr.automation.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

// Groq(OpenAI 호환 Chat Completions API) 설정.
@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("groq")
public class GroqProperties {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final boolean jsonMode;
}
