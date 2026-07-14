package com.pr.automation.webhook.recovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 DB로 ack ledger와 재전송 횟수 기록을 검증
// 운영과 동일하게 각 리포지토리 호출이 독립 트랜잭션이 되도록 테스트 트랜잭션을 끈다
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(DeliveryStore.class)
class DeliveryStoreTest {

    @Autowired
    private DeliveryStore store;

    @Autowired
    private WebhookDeliveryRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void markReceived한_guid는_isReceived_true이다() {
        store.markReceived("a-1");
        assertThat(store.isReceived("a-1")).isTrue();
        assertThat(store.isReceived("a-2")).isFalse();
    }

    @Test
    void null_또는_빈_guid는_무시된다() {
        store.markReceived(null);
        store.markReceived("");
        assertThat(store.isReceived("")).isFalse();
        assertThat(store.isReceived(null)).isFalse();
    }

    @Test
    void 같은_guid를_여러번_markReceived해도_안전하다() {
        store.markReceived("x");
        store.markReceived("x");
        assertThat(store.isReceived("x")).isTrue();
    }

    @Test
    void 재전송_기록은_횟수가_누적되고_isReceived는_false를_유지한다() {
        assertThat(store.getRedeliverCount("r-1")).isZero();

        store.recordRedeliverAttempt("r-1");
        store.recordRedeliverAttempt("r-1");

        assertThat(store.getRedeliverCount("r-1")).isEqualTo(2);
        // RETRYING 이력만 있는 guid는 여전히 복구 대상이어야 함
        assertThat(store.isReceived("r-1")).isFalse();
    }

    @Test
    void 재전송_이력이_있는_guid도_markReceived하면_RECEIVED로_승격된다() {
        store.recordRedeliverAttempt("r-2");
        store.markReceived("r-2");

        assertThat(store.isReceived("r-2")).isTrue();
    }

    @Test
    void RECEIVED된_guid에는_재전송_횟수가_증가하지_않는다() {
        store.markReceived("done");
        store.recordRedeliverAttempt("done");

        assertThat(store.getRedeliverCount("done")).isZero();
        assertThat(store.isReceived("done")).isTrue();
    }
}
