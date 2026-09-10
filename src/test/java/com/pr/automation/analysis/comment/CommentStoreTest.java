package com.pr.automation.analysis.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.CommentAnalysisState;
import com.pr.automation.analysis.comment.CommentAnalysisStateRepository;
import com.pr.automation.analysis.comment.CommentStore;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.analysis.WorkStatus;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
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

// 실제 DB(PK 제약)와 쿼리로 점유/완료/재점유 동작을 검증
// 운영과 동일하게 각 리포지토리 호출이 독립 트랜잭션이 되도록 테스트 트랜잭션을 끈다
// CommentStore가 통지 유예 값을 읽는 CommentAnalyzerProperties를 test application.properties에서 정식 바인딩
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CommentStore.class, ObjectMapper.class}) // @DataJpaTest는 Jackson을 자동 구성하지 않아 직접 등록
@EnableConfigurationProperties(CommentAnalyzerProperties.class)
class CommentStoreTest {

    @Autowired
    private CommentStore store;

    @Autowired
    private CommentAnalysisStateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void clean() {
        // deleteAll()은 쓸 수 없다 — SimpleJpaRepository.delete()가 isNew()인 엔티티를 건너뛰는데
        // 이 엔티티는 INSERT 강제를 위해 isNew()가 항상 true라 한 건도 지워지지 않는다
        repository.deleteAllInBatch();
    }

    @Test
    void tryClaim은_같은_ID에_대해_한_번만_true이다() {
        assertThat(store.tryClaim(event(10L))).isTrue();
        assertThat(store.tryClaim(event(10L))).isFalse();
    }

    @Test
    void markCompleted_후_tryClaim은_false이다() {
        assertThat(store.tryClaim(event(20L))).isTrue();
        store.markCompleted(20L);

        assertThat(store.tryClaim(event(20L))).isFalse();
        assertThat(repository.findById(20L))
                .hasValueSatisfying(s -> assertThat(s.getStatus()).isEqualTo(WorkStatus.COMPLETED));
    }

    @Test
    void markFailed_후에는_유예를_기다리지_않고_다시_tryClaim_가능하다() {
        assertThat(store.tryClaim(event(30L))).isTrue();
        store.markFailed(30L);

        // 행은 남아 있어야 복구 경로가 대상을 찾을 수 있다
        assertThat(repository.findById(30L))
                .hasValueSatisfying(s -> assertThat(s.getStatus()).isEqualTo(WorkStatus.FAILED));
        assertThat(store.tryClaim(event(30L))).isTrue();
    }

    @Test
    void markFailed_후_재점유하면_상태가_IN_PROGRESS로_돌아온다() {
        assertThat(store.tryClaim(event(31L))).isTrue();
        store.markFailed(31L);
        assertThat(store.tryClaim(event(31L))).isTrue();

        // IN_PROGRESS로 돌아와야 이후 실패가 다시 markFailed의 가드에 걸린다
        assertThat(repository.findById(31L))
                .hasValueSatisfying(s -> assertThat(s.getStatus()).isEqualTo(WorkStatus.IN_PROGRESS));
        store.markFailed(31L);
        assertThat(repository.findById(31L))
                .hasValueSatisfying(s -> assertThat(s.getStatus()).isEqualTo(WorkStatus.FAILED));
    }

    @Test
    void markFailed는_완료된_행을_바꾸지_않는다() {
        assertThat(store.tryClaim(event(40L))).isTrue();
        store.markCompleted(40L);
        store.markFailed(40L); // 실패 경로가 아니어도 호출되면 COMPLETED는 보존돼야 함

        assertThat(store.tryClaim(event(40L))).isFalse();
    }

    @Test
    void IN_PROGRESS_점유는_시간이_지나도_탈취되지_않는다() {
        // 시간으로는 죽은 워커와 느린 워커를 구분할 수 없다 - 탈취하면 중복 분석이 되므로 복구는 기동 시 강등에 맡긴다
        assertThat(store.tryClaim(event(50L))).isTrue();
        passNotifyDelay(50L);

        assertThat(store.tryClaim(event(50L))).isFalse();
    }

    @Test
    void 처리_중인_점유는_탈취할_수_없다() {
        assertThat(store.tryClaim(event(60L))).isTrue();
        assertThat(store.tryClaim(event(60L))).isFalse();
    }

    @Test
    void 완료된_행은_시간이_지나도_탈취할_수_없다() {
        assertThat(store.tryClaim(event(70L))).isTrue();
        store.markCompleted(70L);
        passNotifyDelay(70L);

        assertThat(store.tryClaim(event(70L))).isFalse();
    }

    @Test
    void markAnalyzed는_결과를_저장하고_상태를_ANALYZED로_바꾼다() {
        assertThat(store.tryClaim(event(80L))).isTrue();
        store.markAnalyzed(80L, "{\"verdict\":\"ok\"}");

        assertThat(store.findAnalyzedResult(80L)).hasValue("{\"verdict\":\"ok\"}");
        assertThat(repository.findById(80L))
                .hasValueSatisfying(s -> assertThat(s.getStatus()).isEqualTo(WorkStatus.ANALYZED));
    }

    @Test
    void ANALYZED_행은_유예전_재점유불가_유예후_재점유시_결과가_보존된다() {
        assertThat(store.tryClaim(event(81L))).isTrue();
        store.markAnalyzed(81L, "{\"verdict\":\"ok\"}");

        // 통지 재시도 중인 워커의 점유는 뺏지 못함
        assertThat(store.tryClaim(event(81L))).isFalse();

        passNotifyDelay(81L);
        assertThat(store.tryClaim(event(81L))).isTrue();
        // 탈취해도 저장된 결과는 그대로 — 통지만 재시도 가능
        assertThat(store.findAnalyzedResult(81L)).hasValue("{\"verdict\":\"ok\"}");
    }

    @Test
    void markFailed는_ANALYZED_행을_바꾸지_않는다() {
        assertThat(store.tryClaim(event(82L))).isTrue();
        store.markAnalyzed(82L, "{\"verdict\":\"ok\"}");
        store.markFailed(82L); // 통지 실패 경로의 catch에서 무조건 호출됨 — ANALYZED는 보존돼야 함

        assertThat(store.findAnalyzedResult(82L)).hasValue("{\"verdict\":\"ok\"}");
    }

    @Test
    void markCompleted는_결과를_비우고_이후_결과조회는_empty다() {
        assertThat(store.tryClaim(event(83L))).isTrue();
        store.markAnalyzed(83L, "{\"verdict\":\"ok\"}");
        store.markCompleted(83L);

        assertThat(store.findAnalyzedResult(83L)).isEmpty();
        assertThat(repository.findById(83L))
                .hasValueSatisfying(s -> {
                    assertThat(s.getStatus()).isEqualTo(WorkStatus.COMPLETED);
                    assertThat(s.getResultJson()).isNull();
                });
    }

    @Test
    void IN_PROGRESS_행의_결과조회는_empty다() {
        assertThat(store.tryClaim(event(84L))).isTrue();

        assertThat(store.findAnalyzedResult(84L)).isEmpty();
    }

    @Test
    void tryClaim은_이벤트를_JSON으로_보관하고_CommentEvent로_복원된다() throws Exception {
        CommentEvent origin = CommentEvent.builder()
                .commentId(90L)
                .repoFullName("me/repo")
                .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
                .prNumber(7)
                .prTitle("제목")
                .headSha("abc1234")
                .filePath("src/Foo.java")
                .diffHunk("@@ -1,2 +1,3 @@")
                .line(12)
                .side("RIGHT")
                .subjectType("line")
                .commentBody("본문")
                .commentAuthor("reviewer")
                .build();

        assertThat(store.tryClaim(origin)).isTrue();

        String json = repository.findById(90L).orElseThrow(AssertionError::new).getEventJson();
        CommentEvent restored = objectMapper.readValue(json, CommentEvent.class);

        assertThat(restored.getRepoFullName()).isEqualTo("me/repo");
        assertThat(restored.getPrNumber()).isEqualTo(7);
        assertThat(restored.getHeadSha()).isEqualTo("abc1234");
        assertThat(restored.getFilePath()).isEqualTo("src/Foo.java");
        assertThat(restored.getDiffHunk()).isEqualTo("@@ -1,2 +1,3 @@");
        assertThat(restored.getLine()).isEqualTo(12);
        assertThat(restored.getCommentBody()).isEqualTo("본문");
        assertThat(restored.getCommentAuthor()).isEqualTo("reviewer");
        // eventType/subjectType에서 파생되는 판별 메서드도 그대로 계산돼야 한다
        assertThat(restored.isReviewComment()).isTrue();
        assertThat(restored.isFileLevel()).isFalse();
    }

    @Test
    void attemptCount는_최초_점유에_1이고_재점유마다_증가한다() {
        assertThat(store.tryClaim(event(91L))).isTrue();
        assertThat(attemptCount(91L)).isEqualTo(1);

        // 실패 기록만으로는 오르지 않는다 - 실제 점유에 성공한 횟수만 센다
        store.markFailed(91L);
        assertThat(attemptCount(91L)).isEqualTo(1);

        assertThat(store.tryClaim(event(91L))).isTrue(); // reclaimFailed
        assertThat(attemptCount(91L)).isEqualTo(2);

        // 분석 결과 저장만으로도 오르지 않는다
        store.markAnalyzed(91L, "{\"verdict\":\"ok\"}");
        assertThat(attemptCount(91L)).isEqualTo(2);

        passNotifyDelay(91L);
        assertThat(store.tryClaim(event(91L))).isTrue(); // reclaimStaleNotify
        assertThat(attemptCount(91L)).isEqualTo(3);
    }

    @Test
    void 점유에_실패하면_attemptCount는_오르지_않는다() {
        assertThat(store.tryClaim(event(92L))).isTrue();
        assertThat(store.tryClaim(event(92L))).isFalse(); // 처리 중이라 거부

        assertThat(attemptCount(92L)).isEqualTo(1);
    }

    private int attemptCount(long commentId) {
        return repository.findById(commentId).orElseThrow(AssertionError::new).getAttemptCount();
    }

    @Test
    void findRetryTargets는_FAILED와_유예_지난_ANALYZED만_반환한다() {
        store.tryClaim(event(100L));                        // IN_PROGRESS - 대상 아님

        store.tryClaim(event(101L));
        store.markFailed(101L);                             // FAILED - 대상

        store.tryClaim(event(102L));
        store.markAnalyzed(102L, "{}");                     // ANALYZED, 유예 전 - 통지 중일 수 있어 대상 아님

        store.tryClaim(event(103L));
        store.markAnalyzed(103L, "{}");
        passNotifyDelay(103L);                              // ANALYZED, 유예 후 - 대상

        store.tryClaim(event(104L));
        store.markCompleted(104L);                          // COMPLETED - 대상 아님

        assertThat(store.findRetryTargets(10))
                .extracting(CommentEvent::getCommentId)
                .containsExactlyInAnyOrder(101L, 103L);
    }

    @Test
    void findRetryTargets는_attemptCount가_상한에_닿은_건을_제외한다() {
        store.tryClaim(event(105L));
        store.markFailed(105L);
        setAttemptCount(105L, 3); // test application.properties의 retry-max-attempts와 동일

        assertThat(store.findRetryTargets(10)).isEmpty();

        setAttemptCount(105L, 2);
        assertThat(store.findRetryTargets(10))
                .extracting(CommentEvent::getCommentId)
                .containsExactly(105L);
    }

    @Test
    void findRetryTargets는_eventJson이_없는_건을_제외한다() {
        store.tryClaim(event(106L));
        store.markFailed(106L);
        jdbcTemplate.update("update comment_analysis_state set event_json = null where comment_id = ?", 106L);

        // 복원할 수 없는 행은 던져봐야 헛돌므로 애초에 뽑지 않는다
        assertThat(store.findRetryTargets(10)).isEmpty();
    }

    @Test
    void findRetryTargets는_오래된_순으로_limit만큼만_반환한다() {
        for (long id : new long[]{107L, 108L, 109L}) {
            store.tryClaim(event(id));
            store.markFailed(id);
        }
        setClaimedAt(107L, Instant.now().minus(Duration.ofMinutes(30)));
        setClaimedAt(108L, Instant.now().minus(Duration.ofMinutes(20)));
        setClaimedAt(109L, Instant.now().minus(Duration.ofMinutes(10)));

        assertThat(store.findRetryTargets(2))
                .extracting(CommentEvent::getCommentId)
                .containsExactly(107L, 108L);
    }

    @Test
    void demoteStaleInProgress는_IN_PROGRESS만_FAILED로_바꾼다() {
        store.tryClaim(event(110L));                        // IN_PROGRESS
        store.tryClaim(event(111L));
        store.markAnalyzed(111L, "{}");                     // ANALYZED
        store.tryClaim(event(112L));
        store.markCompleted(112L);                          // COMPLETED

        assertThat(store.demoteStaleInProgress()).isEqualTo(1);

        assertThat(status(110L)).isEqualTo(WorkStatus.FAILED);
        assertThat(status(111L)).isEqualTo(WorkStatus.ANALYZED);
        assertThat(status(112L)).isEqualTo(WorkStatus.COMPLETED);
    }

    private WorkStatus status(long commentId) {
        return repository.findById(commentId).orElseThrow(AssertionError::new).getStatus();
    }

    private void setAttemptCount(long commentId, int count) {
        jdbcTemplate.update("update comment_analysis_state set attempt_count = ? where comment_id = ?", count, commentId);
    }

    // tryClaim은 commentId만 쓰지만 시그니처가 이벤트를 받으므로 최소 이벤트를 만들어 넘긴다
    private static CommentEvent event(long commentId) {
        return CommentEvent.builder()
                .commentId(commentId)
                .repoFullName("me/repo")
                .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
                .prNumber(1)
                .build();
    }

    // claimed_at을 통지 유예 이전 시각으로 되돌려 통지 단계에서 끊긴 행을 흉내낸다
    // Hibernate가 hibernate.jdbc.time_zone=UTC로 저장하므로, JDBC 직접 쓰기도 UTC 벽시계로 맞춘다
    private void passNotifyDelay(long commentId) {
        setClaimedAt(commentId, Instant.now().minus(store.notifyRetryDelay()).minus(Duration.ofMinutes(1)));
    }

    private void setClaimedAt(long commentId, Instant at) {
        jdbcTemplate.update("update comment_analysis_state set claimed_at = ? where comment_id = ?",
                Timestamp.valueOf(LocalDateTime.ofInstant(at, ZoneOffset.UTC)), commentId);
    }
}
