package com.pr.automation.webhook.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * ping 수신, 필터 탈락(봇/타인 PR 등), 분석/리뷰 성공 중 하나를 마친 delivery의 guid를 RECEIVED로 기록하는 ledger
 * 분석 도중 크래시난 경우는 RECEIVED 기록이 없어 다음 복구 사이클의 대상이 됩니다.
 * 재전송을 트리거한 횟수(redeliverCount)도 함께 기록해 포이즌 delivery의 무한 재시도를 차단합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryStore {

    private final WebhookDeliveryRepository repository;

    // delivery의 책임 완료를 기록 — RETRYING 이력이 있으면 RECEIVED로 승격
    public void markReceived(String deliveryGuid) {
        if (!StringUtils.hasText(deliveryGuid)) return;
        try {
            repository.saveAndFlush(WebhookDelivery.received(deliveryGuid));
        } catch (DataIntegrityViolationException e) {
            repository.promoteToReceived(deliveryGuid);
        }
    }

    // 해당 guid의 책임이 완료됐는지 조회 (RETRYING 이력만 있는 guid는 false — 여전히 복구 대상)
    public boolean isReceived(String deliveryGuid) {
        return StringUtils.hasText(deliveryGuid)
                && repository.existsByGuidAndStatus(deliveryGuid, DeliveryStatus.RECEIVED);
    }

    // 지금까지 재전송을 트리거한 횟수 (기록 없으면 0)
    public int getRedeliverCount(String deliveryGuid) {
        if (!StringUtils.hasText(deliveryGuid)) return 0;
        return repository.findById(deliveryGuid)
                .map(WebhookDelivery::getRedeliverCount)
                .orElse(0);
    }

    // 재전송 트리거를 기록 — 첫 기록이면 RETRYING 행 생성, 이후는 횟수만 증가
    public void recordRedeliverAttempt(String deliveryGuid) {
        if (!StringUtils.hasText(deliveryGuid)) return;
        try {
            repository.saveAndFlush(WebhookDelivery.retrying(deliveryGuid));
        } catch (DataIntegrityViolationException e) {
            repository.incrementRedeliverCount(deliveryGuid);
        }
    }
}
