package com.pr.automation.review.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// 최종검증 단계가 1~3단계를 종합한 결과 (PR 코멘트로 게시됨)
// overallSummary/mergedFindings/reviewerFocusNotes는 LLM이 채우고, stageSummaries는 파이프라인이 주입
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrReviewResult {
    @JsonProperty("overall_summary")
    private String overallSummary;          // PR 전체 종합 요약

    @JsonProperty("merged_findings")
    private List<ReviewFinding> mergedFindings; // 중복 및 오탐 제거 후 확정 이슈 목록

    @JsonProperty("reviewer_focus_notes")
    private String reviewerFocusNotes;      // 사람 리뷰어가 집중할 본질적 판단 포인트

    private List<String> stageSummaries;    // 단계별 총평 ("언어: ...") - 파이프라인이 설정
}
