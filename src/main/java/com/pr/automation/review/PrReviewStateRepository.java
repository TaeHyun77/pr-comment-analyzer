package com.pr.automation.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface PrReviewStateRepository extends JpaRepository<PrReviewState, String> {

    // 만료된 IN_PROGRESS 점유(죽은 워커)를 탈취하고 claimed_at을 갱신
    @Transactional
    @Modifying
    @Query("update PrReviewState s set s.claimedAt = :now "
            + "where s.prKey = :prKey and s.status = com.pr.automation.common.entity.WorkStatus.IN_PROGRESS "
            + "and s.claimedAt < :expiredBefore")
    int reclaimExpired(@Param("prKey") String prKey, @Param("now") Instant now, @Param("expiredBefore") Instant expiredBefore);

    @Transactional
    @Modifying
    @Query("update PrReviewState s set s.status = com.pr.automation.common.entity.WorkStatus.COMPLETED, s.completedAt = :now "
            + "where s.prKey = :prKey")
    int complete(@Param("prKey") String prKey, @Param("now") Instant now);

    // 점유 행 삭제 — "행 없음 = 재시도 가능" 규약 유지 (COMPLETED 행은 지우지 않음)
    @Transactional
    @Modifying
    @Query("delete from PrReviewState s "
            + "where s.prKey = :prKey and s.status = com.pr.automation.common.entity.WorkStatus.IN_PROGRESS")
    int releaseClaim(@Param("prKey") String prKey);
}
