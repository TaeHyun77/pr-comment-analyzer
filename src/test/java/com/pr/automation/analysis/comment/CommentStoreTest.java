package com.pr.automation.analysis.comment;

import com.pr.automation.common.entity.WorkStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

// 실제 DB(PK 제약)와 쿼리로 점유/완료/lease 동작을 검증
// 운영과 동일하게 각 리포지토리 호출이 독립 트랜잭션이 되도록 테스트 트랜잭션을 끈다
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(CommentStore.class)
class CommentStoreTest {

    @Autowired
    private CommentStore store;

    @Autowired
    private CommentAnalysisStateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void tryClaim은_같은_ID에_대해_한_번만_true이다() {
        assertThat(store.tryClaim(10L)).isTrue();
        assertThat(store.tryClaim(10L)).isFalse();
    }

    @Test
    void markCompleted_후_tryClaim은_false이다() {
        assertThat(store.tryClaim(20L)).isTrue();
        store.markCompleted(20L);

        assertThat(store.tryClaim(20L)).isFalse();
        assertThat(repository.findById(20L))
                .hasValueSatisfying(s -> {
                    assertThat(s.getStatus()).isEqualTo(WorkStatus.COMPLETED);
                    assertThat(s.getCompletedAt()).isNotNull();
                });
    }

    @Test
    void release_후에는_다시_tryClaim_가능하다() {
        assertThat(store.tryClaim(30L)).isTrue();
        store.release(30L);
        assertThat(store.tryClaim(30L)).isTrue();
    }

    @Test
    void release는_완료된_행을_지우지_않는다() {
        assertThat(store.tryClaim(40L)).isTrue();
        store.markCompleted(40L);
        store.release(40L); // 실패 경로가 아니어도 호출되면 COMPLETED는 보존돼야 함

        assertThat(store.tryClaim(40L)).isFalse();
    }

    @Test
    void lease가_만료된_점유는_탈취할_수_있다() {
        assertThat(store.tryClaim(50L)).isTrue();
        expireLease(50L);

        assertThat(store.tryClaim(50L)).isTrue();
    }

    @Test
    void lease가_만료되지_않은_점유는_탈취할_수_없다() {
        assertThat(store.tryClaim(60L)).isTrue();
        assertThat(store.tryClaim(60L)).isFalse();
    }

    @Test
    void 완료된_행은_lease가_지나도_탈취할_수_없다() {
        assertThat(store.tryClaim(70L)).isTrue();
        store.markCompleted(70L);
        expireLease(70L);

        assertThat(store.tryClaim(70L)).isFalse();
    }

    // claimed_at을 lease 만료 이전 시각으로 되돌려 죽은 워커의 점유를 흉내낸다
    // Hibernate가 hibernate.jdbc.time_zone=UTC로 저장하므로, JDBC 직접 쓰기도 UTC 벽시계로 맞춘다
    private void expireLease(long commentId) {
        Instant expired = Instant.now().minus(CommentStore.LEASE_TIMEOUT).minus(Duration.ofMinutes(1));
        jdbcTemplate.update("update comment_analysis_state set claimed_at = ? where comment_id = ?",
                Timestamp.valueOf(LocalDateTime.ofInstant(expired, ZoneOffset.UTC)), commentId);
    }
}
