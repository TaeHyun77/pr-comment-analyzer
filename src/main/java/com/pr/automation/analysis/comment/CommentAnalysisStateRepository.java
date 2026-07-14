package com.pr.automation.analysis.comment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface CommentAnalysisStateRepository extends JpaRepository<CommentAnalysisState, Long> {
    // 만료된 IN_PROGRESS 점유(죽은 워커)를 탈취하고 claimed_at을 갱신
    // WHERE 조건 + 영향 행 수 확인으로 원자성 보장 - 동시에 시도해도 성공하는 워커는 하나뿐
    @Transactional
    @Modifying
    @Query("update CommentAnalysisState s set s.claimedAt = :now "
            + "where s.commentId = :commentId and s.status = com.pr.automation.common.entity.WorkStatus.IN_PROGRESS "
            + "and s.claimedAt < :expiredBefore")
    int reclaimExpired(@Param("commentId") long commentId, @Param("now") Instant now, @Param("expiredBefore") Instant expiredBefore);

    @Transactional
    @Modifying
    @Query("update CommentAnalysisState s set s.status = com.pr.automation.common.entity.WorkStatus.COMPLETED, s.completedAt = :now "
            + "where s.commentId = :commentId")
    int complete(@Param("commentId") long commentId, @Param("now") Instant now);

    // 점유 행 삭제 - "행 없음 = 재시도 가능" 규약 유지 (COMPLETED 행은 지우지 않음)
    @Transactional
    @Modifying
    @Query("delete from CommentAnalysisState s "
            + "where s.commentId = :commentId and s.status = com.pr.automation.common.entity.WorkStatus.IN_PROGRESS")
    int releaseClaim(@Param("commentId") long commentId);
}
