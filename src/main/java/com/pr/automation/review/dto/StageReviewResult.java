package com.pr.automation.review.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// 1~3단계 각각의 리뷰 결과를 담는 DTO
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
