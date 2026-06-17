package com.pr.automation.review.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// PR 생성 웹훅에서 추출한 리뷰 대상 정보
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrReviewEvent {
    private String deliveryId;
    private String repoFullName;
    private int prNumber;
    private String prTitle;
    private String prBody;
    private String headSha;
    private String prAuthor;
    private String prHtmlUrl;
}
