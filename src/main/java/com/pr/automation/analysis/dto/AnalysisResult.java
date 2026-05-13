package com.pr.automation.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// LLM의 응답
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {
    @JsonProperty("comment_summary")
    private String commentSummary; // 코멘트가 무엇을 지적/제안하는지 핵심 요약

    @JsonProperty("current_approach")
    private String currentApproach; // 현재 구현 방식 (해당 없으면 "해당 없음")

    @JsonProperty("suggested_approach")
    private String suggestedApproach; // 판정 : "제안 채택 권장" / "현 구현 유지 권장" / "추가 논의 필요" + 한 줄 이유

    private String verdict;

    private String reasoning; // 판정 근거 (트레이드오프 포함)

    @JsonProperty("suggested_reply")
    private String suggestedReply; // 코멘트에 달 답변 초안
}