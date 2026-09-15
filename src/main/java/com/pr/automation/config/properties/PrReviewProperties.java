package com.pr.automation.config.properties;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

// PR 생성 시 자동 리뷰 설정
@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("pr-review")
public class PrReviewProperties {
    private final boolean enabled;
}
