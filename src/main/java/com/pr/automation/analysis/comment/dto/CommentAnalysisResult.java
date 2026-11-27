package com.pr.automation.analysis.comment.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pr.automation.llm.dto.LlmUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 에이전트가 마지막에 출력한 결과 JSON을 파싱해 담는 DTO
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommentAnalysisResult {
    // 질문이나 주장이 없는 코멘트(감사, 동의 등)에 LLM이 내리는 판정 - 코드 탐색 없이 끝나며 Slack도 짧게 보낸다
    public static final String VERDICT_SKIPPED = "분석 생략";

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

    @Setter private LlmUsage usage;

    @JsonIgnore
    public boolean isSkipped() {
        return verdict != null && verdict.trim().startsWith(VERDICT_SKIPPED);
    }
}