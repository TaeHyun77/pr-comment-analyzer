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

    // 자율 탐색 에이전트 루프 예산(균형 기본값). 0/음수면 폴백(단발) 경로만 사용.
    private final int maxToolIterations; // 최대 툴 라운드 수
    private final int maxFiles; // 한 분석에서 read_file로 열 수 있는 최대 파일 수
    private final int maxFileChars; // 파일 1개를 LLM에 전달할 때의 문자 상한(토큰 보호)
}
