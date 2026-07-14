package com.pr.automation.common.entity;

// 작업(코멘트 분석/PR 리뷰) 점유 상태
public enum WorkStatus {
    IN_PROGRESS, // 워커가 점유하고 처리 중 (lease 만료 시 다른 워커가 탈취 가능)
    COMPLETED    // 처리 완료 - 재실행 금지
}
