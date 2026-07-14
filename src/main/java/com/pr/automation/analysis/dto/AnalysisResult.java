package com.pr.automation.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// LLM이 submit_analysis 도구로 제출하는 최종 분석 결과를 담는 DTO
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {
    @JsonProperty("comment_summary")
    private String commentSummary; // 코멘트 요약

    @JsonProperty("current_approach")
    private String currentApproach; // 현재 구현 방식

    @JsonProperty("suggested_approach")
    private String suggestedApproach; // 리뷰어가 제안하는 대안 방식

    private String verdict; // AI의 판정 - "제안 채택 권장" / "현 구현 유지 권장" / "추가 논의 필요" + 한 줄 이유

    private String reasoning; // AI의 판정 근거 (트레이드오프 포함한 상세 설명)

    @JsonProperty("suggested_reply")
    private String suggestedReply; // 리뷰어 코멘트에 달 답변 초안
}