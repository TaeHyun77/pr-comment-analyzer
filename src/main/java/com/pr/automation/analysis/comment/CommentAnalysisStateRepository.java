package com.pr.automation.analysis.comment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface CommentAnalysisStateRepository extends JpaRepository<CommentAnalysisState, Long> {
    // 통지 유예가 지난 ANALYZED 행을 재점유하고 claimed_at을 갱신
    // WHERE 조건 + 영향 행 수 확인으로 원자성 보장 - 동시에 시도해도 성공하는 워커는 하나뿐
    // resultJson은 건드리지 않아 재점유한 워커가 저장된 결과로 통지만 재시도할 수 있음
    // IN_PROGRESS는 대상이 아니다 - 시간으로는 죽은 워커와 느린 워커를 구분할 수 없어 탈취하면 중복 분석이 된다.
    // 잔존 IN_PROGRESS는 기동 시 FAILED로 강등해 복구하므로 여기서 다룰 이유가 없다
    // attemptCount는 점유에 성공했을 때만 올린다 - 거부된 시도까지 세면 재시도 상한이 헛되이 소진된다
    @Transactional
    @Modifying
    @Query("update CommentAnalysisState s set s.claimedAt = :now, s.attemptCount = s.attemptCount + 1 "
            + "where s.commentId = :commentId "
            + "and s.status = com.pr.automation.analysis.WorkStatus.ANALYZED "
            + "and s.claimedAt < :retryableBefore")
    int reclaimStaleNotify(@Param("commentId") long commentId, @Param("now") Instant now, @Param("retryableBefore") Instant retryableBefore);

    // 분석 결과를 저장하고 통지 대기 상태로 전환. claimed_at도 갱신해 통지 단계가 유예를 온전히 갖게 함
    @Transactional
    @Modifying
    @Query("update CommentAnalysisState s set s.status = com.pr.automation.analysis.WorkStatus.ANALYZED, "
            + "s.resultJson = :resultJson, s.claimedAt = :now "
            + "where s.commentId = :commentId")
    int markAnalyzed(@Param("commentId") long commentId, @Param("resultJson") String resultJson, @Param("now") Instant now);

    // 완료 시 resultJson을 비움 - 결과는 통지 재시도용이므로 완료 후 보관하지 않음
    @Transactional
    @Modifying
    @Query("update CommentAnalysisState s set s.status = com.pr.automation.analysis.WorkStatus.COMPLETED, "
            + "s.resultJson = null "
            + "where s.commentId = :commentId")
    int complete(@Param("commentId") long commentId);

    // 실패를 FAILED로 기록 - 행을 지우지 않아야 복구 경로가 재시도 대상을 찾을 수 있음
    // IN_PROGRESS만 대상으로 해 ANALYZED(통지만 실패)의 resultJson과 COMPLETED를 보존한다
    @Transactional
    @Modifying
    @Query("update CommentAnalysisState s set s.status = com.pr.automation.analysis.WorkStatus.FAILED, "
            + "s.claimedAt = :now "
            + "where s.commentId = :commentId and s.status = com.pr.automation.analysis.WorkStatus.IN_PROGRESS")
    int markFailed(@Param("commentId") long commentId, @Param("now") Instant now);

    // 실패 후 대기 중인 행을 재점유 - 작업 중인 워커가 없으므로 시간 조건을 두지 않는다
    // 상태를 IN_PROGRESS로 되돌려야 이후 다시 실패했을 때 markFailed의 가드에 걸린다
    @Transactional
    @Modifying
    @Query("update CommentAnalysisState s set s.status = com.pr.automation.analysis.WorkStatus.IN_PROGRESS, "
            + "s.claimedAt = :now, s.attemptCount = s.attemptCount + 1 "
            + "where s.commentId = :commentId and s.status = com.pr.automation.analysis.WorkStatus.FAILED")
    int reclaimFailed(@Param("commentId") long commentId, @Param("now") Instant now);

    /**
     * 자동 재시도 대상을 오래된 순으로 조회합니다.
     * FAILED는 작업 중인 워커가 없어 시간 조건 없이, ANALYZED는 통지 중인 워커를 뺏지 않도록 유예가 지난 것만 대상입니다.
     * eventJson이 없으면 CommentEvent를 복원할 수 없어 던져봐야 헛돌므로 쿼리 단계에서 제외합니다.
     * IN_PROGRESS는 대상이 아닙니다 — 기동 시 demoteAllInProgress로 FAILED가 된 뒤에야 여기 걸립니다.
     */
    @Query("select s from CommentAnalysisState s "
            + "where (s.status = com.pr.automation.analysis.WorkStatus.FAILED "
            + "   or (s.status = com.pr.automation.analysis.WorkStatus.ANALYZED and s.claimedAt < :notifyRetryableBefore)) "
            + "and s.attemptCount < :maxAttempts "
            + "and s.eventJson is not null "
            + "order by s.claimedAt asc")
    List<CommentAnalysisState> findRetryTargets(@Param("notifyRetryableBefore") Instant notifyRetryableBefore,
                                                @Param("maxAttempts") int maxAttempts,
                                                Pageable pageable);

    // 기동 시 잔존 IN_PROGRESS를 일괄 강등 - 이 시점의 점유는 모두 이전 프로세스의 유산이다
    // 단일 인스턴스 전제. 다중 인스턴스에서는 다른 인스턴스가 처리 중인 행까지 강등된다
    @Transactional
    @Modifying
    @Query("update CommentAnalysisState s set s.status = com.pr.automation.analysis.WorkStatus.FAILED, "
            + "s.claimedAt = :now "
            + "where s.status = com.pr.automation.analysis.WorkStatus.IN_PROGRESS")
    int demoteAllInProgress(@Param("now") Instant now);
}
