package com.pr.automation.config.properties;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("comment-analyzer")
public class CommentAnalyzerProperties {
    private final boolean includeOwnComments;
    private final boolean enabled; // 코멘트 분석 전역 on/off, false면 분석을 트리거하지 않음
}
