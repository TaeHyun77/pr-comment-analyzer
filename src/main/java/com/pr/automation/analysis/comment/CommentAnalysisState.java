package com.pr.automation.analysis.comment;

import com.pr.automation.common.entity.WorkStatus;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.Instant;

// 코멘트 분석 점유/완료 상태. PK(comment_id) 제약이 인스턴스 간 원자적 점유를 보장한다
@Getter
@Entity
@Table(name = "comment_analysis_state")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommentAnalysisState implements Persistable<Long> {
    @Id
    @Column(name = "comment_id")
    private Long commentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkStatus status; // IN_PROGRESS or COMPLETED

    // lease 기준 시각 - 만료된 IN_PROGRESS 점유는 다른 워커가 탈취하며 이 값을 갱신
    @Column(nullable = false)
    private Instant claimedAt;

    private Instant completedAt;

    // ANALYZED 상태에서만 값이 있음 - 통지 실패 시 재분석 없이 이 결과로 통지만 재시도
    // (@Lob: MySQL longtext / 테스트 H2 clob 양쪽 호환. 완료 시 null로 비움)
    @Lob
    private String resultJson;

    private CommentAnalysisState(Long commentId, Instant claimedAt) {
        this.commentId = commentId;
        this.status = WorkStatus.IN_PROGRESS;
        this.claimedAt = claimedAt;
    }

    // IN_PROGRESS 점유 행 생성
    public static CommentAnalysisState claim(long commentId, Instant now) {
        return new CommentAnalysisState(commentId, now);
    }

    @Override
    public Long getId() {
        return commentId;
    }

    // 항상 신규로 취급해 save가 select 없이 INSERT를 시도하게 함 (중복 키 실패 = 이미 점유됨 신호)
    // 삽입 후 변경은 전부 리포지토리의 @Modifying 쿼리로만 수행하므로 merge 경로는 사용하지 않음
    @Override
    public boolean isNew() {
        return true;
    }
}
