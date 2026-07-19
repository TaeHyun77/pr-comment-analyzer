package com.pr.automation.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

// PR 생성 시 4단계 자동 리뷰 설정
@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("pr-review")
public class PrReviewProperties {
    private final boolean enabled;
    private final int maxFiles;           // diff에서 LLM에 전달할 최대 파일 수
    private final int maxPatchChars;      // 파일 1개 patch의 문자 상한(토큰 보호)
    private final boolean postToSlack;    // 결과를 Slack에도 보낼지 여부

    // 점유 lease 만료 시간(분). 분석 단계 최악 지연(4호출 × 재시도 × read timeout)을 넘겨야
    // 느리지만 살아있는 워커의 점유를 탈취하지 않음 — 탈취 시 전체 재분석 + 게시 중복 시도 발생
    private final int leaseTimeoutMinutes;
}
