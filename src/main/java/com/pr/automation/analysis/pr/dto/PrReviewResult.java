package com.pr.automation.analysis.pr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pr.automation.llm.dto.LlmUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// PR 리뷰 에이전트가 submit_review로 제출하는 최종 결과
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrReviewResult {
    @JsonProperty("overall_summary")
    private String overallSummary;

    // submit_review 도구의 "findings" 필드로 수신 후 mergedFindings에 매핑
    @JsonProperty("findings")
    private List<ReviewFinding> findings;

    @JsonProperty("merged_findings")
    private List<ReviewFinding> mergedFindings;

    @JsonProperty("reviewer_focus_notes")
    private String reviewerFocusNotes;

    // 운영 메타데이터 — Claude 응답이 아닌 파이프라인에서 채워짐
    private Integer roundsUsed;
    private Integer filesReadCount;
    private LlmUsage usage;
}
