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

// PR 리뷰 에이전트가 마지막에 출력한 결과 JSON을 파싱해 담는 DTO
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrReviewResult {
    @JsonProperty("overall_summary")
    private String overallSummary;

    // 에이전트가 출력한 JSON의 "findings"로 수신 후 mergedFindings에 매핑
    @JsonProperty("findings")
    private List<ReviewFinding> findings;

    @JsonProperty("merged_findings")
    private List<ReviewFinding> mergedFindings;

    @JsonProperty("reviewer_focus_notes")
    private String reviewerFocusNotes;

    // 토큰 사용량. 에이전트 응답이 아니라 CLI 실행 결과에서 채워진다
    private LlmUsage usage;
}
