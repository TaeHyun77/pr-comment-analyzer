package com.pr.automation.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("pr-analyzer")
public class PrAnalyzerProperties {

    private final boolean includeOwnComments;
    private final String stateFile;
    private final int fileContextLines;
}
