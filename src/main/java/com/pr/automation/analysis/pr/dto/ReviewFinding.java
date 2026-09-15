package com.pr.automation.analysis.pr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// AI 리뷰 결과 JSON에서 이슈 한 건 (파일, 줄, 심각도, 분류, 제목, 설명, 제안)을 담는 DTO
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewFinding {
    private String file;       // 이슈가 위치한 파일 경로 (불명확하면 "전반")
    private Integer line;      // 라인 번호 (없으면 null)
    private String severity;   // "높음" | "중간" | "낮음"
    private String category;   // 분류 (예: null-safety, 예외처리, 보안 등)
    private String title;      // 한 줄 요약
    private String detail;     // 구체적 설명
    private String suggestion; // 개선 제안
}
