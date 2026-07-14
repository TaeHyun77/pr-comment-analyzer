package com.pr.automation.review;

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
import javax.persistence.Table;
import java.time.Instant;

// PR 리뷰 점유/완료 상태. CommentAnalysisState와 동일 패턴 — 키 타입만 String(repo#pr)
@Getter
@Entity
@Table(name = "pr_review_state")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrReviewState implements Persistable<String> {
    @Id
    @Column(name = "pr_key", length = 255)
    private String prKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkStatus status;

    // lease 기준 시각 — 만료된 IN_PROGRESS 점유는 다른 워커가 탈취하며 이 값을 갱신
    @Column(nullable = false)
    private Instant claimedAt;

    private Instant completedAt;

    private PrReviewState(String prKey, Instant claimedAt) {
        this.prKey = prKey;
        this.status = WorkStatus.IN_PROGRESS;
        this.claimedAt = claimedAt;
    }

    // IN_PROGRESS 점유 행 생성
    public static PrReviewState claim(String prKey, Instant now) {
        return new PrReviewState(prKey, now);
    }

    @Override
    public String getId() {
        return prKey;
    }

    // 항상 신규로 취급해 save가 select 없이 INSERT를 시도하게 함 (중복 키 실패 = 이미 점유됨 신호)
    @Override
    public boolean isNew() {
        return true;
    }
}
