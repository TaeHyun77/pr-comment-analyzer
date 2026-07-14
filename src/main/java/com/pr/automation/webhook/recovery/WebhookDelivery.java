package com.pr.automation.webhook.recovery;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

// delivery ack ledger — RECEIVED 행 존재가 "책임을 다 졌다"는 기록이며, 없으면 복구 대상
@Getter
@Entity
@Table(name = "webhook_delivery")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDelivery implements Persistable<String> {

    @Id
    @Column(length = 100)
    private String guid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    // redeliver를 트리거한 횟수 — 상한 초과 시 포이즌 delivery로 간주하고 복구를 포기
    @Column(nullable = false)
    private int redeliverCount;

    private WebhookDelivery(String guid, DeliveryStatus status, int redeliverCount) {
        this.guid = guid;
        this.status = status;
        this.redeliverCount = redeliverCount;
    }

    public static WebhookDelivery received(String guid) {
        return new WebhookDelivery(guid, DeliveryStatus.RECEIVED, 0);
    }

    public static WebhookDelivery retrying(String guid) {
        return new WebhookDelivery(guid, DeliveryStatus.RETRYING, 1);
    }

    @Override
    public String getId() {
        return guid;
    }

    // 항상 신규로 취급해 save가 select 없이 INSERT를 시도하게 함 (중복 키 실패 시 UPDATE 쿼리로 전환)
    @Override
    public boolean isNew() {
        return true;
    }
}
