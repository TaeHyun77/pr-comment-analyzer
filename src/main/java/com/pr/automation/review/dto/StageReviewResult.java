package com.pr.automation.review.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// 단계별(언어/프레임워크·인프라/도메인·보안) 리뷰 결과
// findings/summary는 LLM이 채우고, stageName은 파이프라인이 주입
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StageReviewResult {
    private String stageName;          // 단계 이름 (파이프라인이 설정)
    private List<ReviewFinding> findings;
    private String summary;            // 단계 총평
}
