package com.pr.automation.webhook.recovery;

// webhook delivery 처리 상태
public enum DeliveryStatus {
    RECEIVED, // 책임을 다 진 delivery (분석 성공/필터 탈락/ping) - 복구 대상에서 제외
    RETRYING  // 재전송을 트리거한 이력만 있는 delivery - 여전히 복구 대상이며 횟수 상한 판단에 사용
}
