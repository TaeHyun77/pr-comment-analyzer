package com.pr.automation.llm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmUsage {
    private final long inputTokens; // 캐시에 없어 새로 처리한 입력
    private final long cacheCreationTokens; // 캐시에 기록한 입력 - 입력가의 1.25배로 과금
    private final long cacheReadTokens; // 캐시에서 재사용한 입력 - 입력가의 0.1배로 과금
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
