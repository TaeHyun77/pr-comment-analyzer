package com.pr.automation.analysis.comment;

import com.pr.automation.analysis.WorkStatus;
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
    private WorkStatus status;

    // 마지막 점유/상태 전환 시각 - ANALYZED의 통지 유예 판정 기준이며 재점유 때 갱신된다.
    // IN_PROGRESS는 이 값으로 탈취되지 않는다 (기동 시 FAILED 강등으로만 복구)
    @Column(nullable = false)
    private Instant claimedAt;

    // ANALYZED 상태에서만 값이 있음 - 통지 실패 시 재분석 없이 이 결과로 통지만 재시도
    @Lob
    private String resultJson;

    // 재시도 시 CommentEvent를 되살리기 위한 원본 - 웹훅 payload는 요청 종료와 함께 사라짐
    // 직렬화에 실패하면 null이며, 그 행은 복구 대상이 되어도 이벤트를 복원할 수 없음
    @Lob
    private String eventJson;

    // 점유에 성공한 누적 횟수 - 최초 INSERT가 1, 재점유마다 1씩 증가해 상한 초과 시 복구를 포기한다
    @Column(nullable = false)
    private int attemptCount;

    private CommentAnalysisState(Long commentId, Instant claimedAt, String eventJson) {
        this.commentId = commentId;
        this.status = WorkStatus.IN_PROGRESS;
        this.claimedAt = claimedAt;
        this.eventJson = eventJson;
        this.attemptCount = 1;
    }

    // IN_PROGRESS 점유 행 생성
    public static CommentAnalysisState claim(long commentId, Instant now, String eventJson) {
        return new CommentAnalysisState(commentId, now, eventJson);
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
