package com.pr.automation.webhook.recovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, String> {

    boolean existsByGuidAndStatus(String guid, DeliveryStatus status);

    // RETRYING 행을 RECEIVED로 승격 (재전송 후 처리에 성공한 경우)
    @Transactional
    @Modifying
    @Query("update WebhookDelivery d set d.status = com.pr.automation.webhook.recovery.DeliveryStatus.RECEIVED "
            + "where d.guid = :guid")
    int promoteToReceived(@Param("guid") String guid);

    // 재전송 트리거 횟수 증가 (RECEIVED로 승격된 행은 건드리지 않음)
    @Transactional
    @Modifying
    @Query("update WebhookDelivery d set d.redeliverCount = d.redeliverCount + 1 "
            + "where d.guid = :guid and d.status = com.pr.automation.webhook.recovery.DeliveryStatus.RETRYING")
    int incrementRedeliverCount(@Param("guid") String guid);
}
