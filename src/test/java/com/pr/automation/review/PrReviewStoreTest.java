package com.pr.automation.review;

import com.pr.automation.common.entity.WorkStatus;
import com.pr.automation.config.PrReviewProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 DB(PK 제약)와 쿼리로 점유/완료/lease/결과 영속화 동작을 검증 - CommentStoreTest와 동일 패턴
// 운영과 동일하게 각 리포지토리 호출이 독립 트랜잭션이 되도록 테스트 트랜잭션을 끈다
// PrReviewStore가 lease 값을 읽는 PrReviewProperties를 test application.properties에서 정식 바인딩
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(PrReviewStore.class)
@EnableConfigurationProperties(PrReviewProperties.class)
class PrReviewStoreTest {

    @Autowired
    private PrReviewStore store;

    @Autowired
    private PrReviewStateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void tryClaim은_같은_키에_대해_한_번만_true이다() {
        assertThat(store.tryClaim("me/repo#1")).isTrue();
        assertThat(store.tryClaim("me/repo#1")).isFalse();
    }

    @Test
    void markAnalyzed는_결과를_저장하고_상태를_ANALYZED로_바꾼다() {
        assertThat(store.tryClaim("me/repo#2")).isTrue();
        store.markAnalyzed("me/repo#2", "{\"overall_summary\":\"ok\"}");

        assertThat(store.findAnalyzedResult("me/repo#2")).hasValue("{\"overall_summary\":\"ok\"}");
        assertThat(repository.findById("me/repo#2"))
                .hasValueSatisfying(s -> assertThat(s.getStatus()).isEqualTo(WorkStatus.ANALYZED));
    }

    @Test
    void ANALYZED_행은_lease_만료전_탈취불가_만료후_탈취시_결과가_보존된다() {
        assertThat(store.tryClaim("me/repo#3")).isTrue();
        store.markAnalyzed("me/repo#3", "{\"overall_summary\":\"ok\"}");

        // 게시 재시도 중인 워커의 점유는 뺏지 못함
        assertThat(store.tryClaim("me/repo#3")).isFalse();

        expireLease("me/repo#3");
        assertThat(store.tryClaim("me/repo#3")).isTrue();
        // 탈취해도 저장된 결과는 그대로 — 게시만 재시도 가능
        assertThat(store.findAnalyzedResult("me/repo#3")).hasValue("{\"overall_summary\":\"ok\"}");
    }

    @Test
    void release는_ANALYZED_행을_지우지_않는다() {
        assertThat(store.tryClaim("me/repo#4")).isTrue();
        store.markAnalyzed("me/repo#4", "{\"overall_summary\":\"ok\"}");
        store.release("me/repo#4"); // 게시 실패 경로의 catch에서 무조건 호출됨 — ANALYZED는 보존돼야 함

        assertThat(store.findAnalyzedResult("me/repo#4")).hasValue("{\"overall_summary\":\"ok\"}");
    }

    @Test
    void release_후에는_다시_tryClaim_가능하다() {
        assertThat(store.tryClaim("me/repo#5")).isTrue();
        store.release("me/repo#5");
        assertThat(store.tryClaim("me/repo#5")).isTrue();
    }

    @Test
    void markCompleted는_결과를_비우고_이후_결과조회는_empty다() {
        assertThat(store.tryClaim("me/repo#6")).isTrue();
        store.markAnalyzed("me/repo#6", "{\"overall_summary\":\"ok\"}");
        store.markCompleted("me/repo#6");

        assertThat(store.findAnalyzedResult("me/repo#6")).isEmpty();
        assertThat(repository.findById("me/repo#6"))
                .hasValueSatisfying(s -> {
                    assertThat(s.getStatus()).isEqualTo(WorkStatus.COMPLETED);
                    assertThat(s.getResultJson()).isNull();
                });
    }

    @Test
    void 완료된_행은_lease가_지나도_탈취할_수_없다() {
        assertThat(store.tryClaim("me/repo#7")).isTrue();
        store.markCompleted("me/repo#7");
        expireLease("me/repo#7");

        assertThat(store.tryClaim("me/repo#7")).isFalse();
    }

    @Test
    void IN_PROGRESS_행의_결과조회는_empty다() {
        assertThat(store.tryClaim("me/repo#8")).isTrue();

        assertThat(store.findAnalyzedResult("me/repo#8")).isEmpty();
    }

    // claimed_at을 lease 만료 이전 시각으로 되돌려 죽은 워커의 점유를 흉내낸다
    // Hibernate가 hibernate.jdbc.time_zone=UTC로 저장하므로, JDBC 직접 쓰기도 UTC 벽시계로 맞춘다
    private void expireLease(String prKey) {
        Instant expired = Instant.now().minus(store.leaseTimeout()).minus(Duration.ofMinutes(1));
        jdbcTemplate.update("update pr_review_state set claimed_at = ? where pr_key = ?",
                Timestamp.valueOf(LocalDateTime.ofInstant(expired, ZoneOffset.UTC)), prKey);
    }
}
