package com.pr.automation.llm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * claude --output-format json 응답에서 파싱한 호출 1회의 자원 사용량
 * 비용은 모델과 요금제에 따라 변하는 파생값이므로 참고용이며, 프롬프트 구성을 직접 반영하는 토큰을 기본 지표로 쓴다.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmUsage {
    private final long inputTokens;         // 캐시에 없어 새로 처리한 입력
    private final long cacheCreationTokens; // 캐시에 기록한 입력 - 입력가의 1.25배로 과금
    private final long cacheReadTokens;     // 캐시에서 재사용한 입력 - 입력가의 0.1배로 과금
    private final long outputTokens;
    private final long durationMs;
    private final double costUsd;

    @JsonCreator
    public LlmUsage(
            @JsonProperty("inputTokens") long inputTokens,
            @JsonProperty("cacheCreationTokens") long cacheCreationTokens,
            @JsonProperty("cacheReadTokens") long cacheReadTokens,
            @JsonProperty("outputTokens") long outputTokens,
            @JsonProperty("durationMs") long durationMs,
            @JsonProperty("costUsd") double costUsd) {
        this.inputTokens = inputTokens;
        this.cacheCreationTokens = cacheCreationTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.outputTokens = outputTokens;
        this.durationMs = durationMs;
        this.costUsd = costUsd;
    }

    public static LlmUsage zero() {
        return new LlmUsage(0L, 0L, 0L, 0L, 0L, 0.0);
    }

    // 예산 집행 기준 - 캐시 적중분까지 포함한 전체 토큰
    // (@JsonIgnore: 파생값이라 직렬화하지 않는다 — 저장 후 복원 시 중복/불일치를 만들지 않기 위함)
    @JsonIgnore
    public long getTotalTokens() {
        return inputTokens + cacheCreationTokens + cacheReadTokens + outputTokens;
    }

    public LlmUsage add(LlmUsage other) {
        if (other == null) {
            return this;
        }
        return new LlmUsage(
                this.inputTokens + other.inputTokens,
                this.cacheCreationTokens + other.cacheCreationTokens,
                this.cacheReadTokens + other.cacheReadTokens,
                this.outputTokens + other.outputTokens,
                this.durationMs + other.durationMs,
                this.costUsd + other.costUsd);
    }
}
