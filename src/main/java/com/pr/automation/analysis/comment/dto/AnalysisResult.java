package com.pr.automation.analysis.comment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pr.automation.llm.dto.LlmUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// LLM이 submit_analysis 도구로 제출하는 최종 분석 결과를 담는 DTO
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
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

    // 운영 메타데이터 — Claude 응답이 아닌 에이전트에서 채워짐
    @Setter private Integer roundsUsed;
    @Setter private Integer filesReadCount;

    // 토큰 사용량(비용 포함). 비용은 모델/요금제에 따라 변하는 파생값이라 토큰을 기본 지표로 본다
    @Setter private LlmUsage usage;

    // 이 분석에 허용된 누적 토큰 상한. 0 이하면 예산 제한 없음
    @Setter private Long tokenBudget;
}