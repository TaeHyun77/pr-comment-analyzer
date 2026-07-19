package com.pr.automation.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface PrReviewStateRepository extends JpaRepository<PrReviewState, String> {

    // 만료된 IN_PROGRESS/ANALYZED 점유(죽은 워커)를 탈취하고 claimed_at을 갱신
    // ANALYZED의 resultJson은 건드리지 않아 탈취한 워커가 저장된 결과로 게시만 재시도할 수 있음
    @Transactional
    @Modifying
    @Query("update PrReviewState s set s.claimedAt = :now "
            + "where s.prKey = :prKey "
            + "and s.status <> com.pr.automation.common.entity.WorkStatus.COMPLETED "
            + "and s.claimedAt < :expiredBefore")
    int reclaimExpired(@Param("prKey") String prKey, @Param("now") Instant now, @Param("expiredBefore") Instant expiredBefore);

    // 리뷰 결과를 저장하고 게시 대기 상태로 전환. claimed_at도 갱신해 게시 단계가 새 lease를 온전히 갖게 함
    @Transactional
    @Modifying
    @Query("update PrReviewState s set s.status = com.pr.automation.common.entity.WorkStatus.ANALYZED, "
            + "s.resultJson = :resultJson, s.claimedAt = :now "
            + "where s.prKey = :prKey")
    int markAnalyzed(@Param("prKey") String prKey, @Param("resultJson") String resultJson, @Param("now") Instant now);

    // 완료 시 resultJson을 비움 - 결과는 게시 재시도용이므로 완료 후 보관하지 않음
    @Transactional
    @Modifying
    @Query("update PrReviewState s set s.status = com.pr.automation.common.entity.WorkStatus.COMPLETED, "
            + "s.completedAt = :now, s.resultJson = null "
            + "where s.prKey = :prKey")
    int complete(@Param("prKey") String prKey, @Param("now") Instant now);

    // 점유 행 삭제 — "행 없음 = 재시도 가능" 규약 유지 (COMPLETED 행은 지우지 않음)
    @Transactional
    @Modifying
    @Query("delete from PrReviewState s "
            + "where s.prKey = :prKey and s.status = com.pr.automation.common.entity.WorkStatus.IN_PROGRESS")
    int releaseClaim(@Param("prKey") String prKey);
}
