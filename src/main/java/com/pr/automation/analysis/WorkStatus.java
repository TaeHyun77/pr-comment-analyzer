package com.pr.automation.analysis;

// 코멘트 분석 작업의 점유 상태
public enum WorkStatus {
    // 재점유를 허용하는 조건은 CommentStore에서 정한다
    IN_PROGRESS, // 워커가 점유하고 처리 중
    ANALYZED,    // 분석 완료 + 결과 저장됨, 통지/게시 대기 - 재점유 시 분석을 건너뛰고 통지만 수행
    FAILED,      // 처리 실패 - 작업 중인 워커가 없어 시간 조건 없이 즉시 재점유 가능
    COMPLETED    // 처리 완료 - 재실행 금지
}
