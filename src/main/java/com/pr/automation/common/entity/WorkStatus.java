package com.pr.automation.common.entity;

// 작업(코멘트 분석/PR 리뷰) 점유 상태
public enum WorkStatus {
    IN_PROGRESS, // 워커가 점유하고 처리 중 (lease 만료 시 다른 워커가 탈취 가능)
    ANALYZED,    // 분석 완료 + 결과 저장됨, 통지 대기 - 재시도 시 분석을 건너뛰고 통지만 수행 (lease 탈취 가능)
    COMPLETED    // 처리 완료 - 재실행 금지
}
