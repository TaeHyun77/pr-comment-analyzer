package com.pr.automation.analysis.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.analysis.WorkStatus;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// 코멘트 중복 처리 방지 컴포넌트 — DB의 PK 제약으로 확인/등록을 원자화해 다중 인스턴스에서도 하나만 점유
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentStore {
    private final CommentAnalysisStateRepository repository;
    private final CommentAnalyzerProperties properties;
    private final ObjectMapper objectMapper;

    // ANALYZED 행을 다시 집기까지의 유예 - 통지 중인 워커를 뺏지 않기 위한 최소 대기
    Duration notifyRetryDelay() {
        return Duration.ofMinutes(properties.getNotifyRetryDelayMinutes());
    }

    // 점유 시도 - 이미 완료됐거나 다른 워커가 처리 중이면 false, 점유에 성공하면 true 반환
    public boolean tryClaim(CommentEvent event) {
        long commentId = event.getCommentId();

        try {
            repository.saveAndFlush(CommentAnalysisState.claim(commentId, Instant.now(), serializeEvent(event)));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 이미 행이 있을 때 - 통지 유예가 지난 ANALYZED면 재점유, 실패 후 대기 중이면 즉시 재점유
            // IN_PROGRESS와 COMPLETED는 어느 쪽에도 걸리지 않아 포기한다.
            // IN_PROGRESS를 시간으로 탈취하지 않는 이유는 reclaimStaleNotify 주석 참조
            // 상태는 상호 배타적이라 둘 중 하나만 매칭된다
            Instant now = Instant.now();
            return repository.reclaimStaleNotify(commentId, now, now.minus(notifyRetryDelay())) == 1
                    || repository.reclaimFailed(commentId, now) == 1;
        }
    }

    // 재시도 복구용 이벤트 사본 - 직렬화 실패가 점유를 막지는 않는다.
    // 분석은 그대로 진행되고, 대신 그 행은 복구 대상이 되어도 이벤트를 되살릴 수 없다
    private String serializeEvent(CommentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("이벤트 직렬화 실패 - 재시도 시 복원 불가: comment={}", event.getCommentId(), e);
            return null;
        }
    }

    // 분석 결과를 저장하고 ANALYZED 상태로 전환
    public void markAnalyzed(long commentId, String resultJson) {
        repository.markAnalyzed(commentId, resultJson, Instant.now());
    }

    // ANALYZED 상태인 행의 저장된 결과 JSON을 반환, 그 외 상태면 empty
    public Optional<String> findAnalyzedResult(long commentId) {
        return repository.findById(commentId)
                .filter(s -> s.getStatus() == WorkStatus.ANALYZED)
                .map(CommentAnalysisState::getResultJson);
    }

    // 점유를 완료 상태로 전환 - 이후 같은 ID의 tryClaim은 항상 false
    public void markCompleted(long commentId) {
        repository.complete(commentId);
    }

    // 분석 실패를 FAILED로 기록해 같은 ID의 재시도를 허용 - 행을 남겨야 복구 경로가 대상을 찾을 수 있음
    public void markFailed(long commentId) {
        repository.markFailed(commentId, Instant.now());
    }

    /**
     * 자동 재시도 대상 이벤트를 오래된 순으로 최대 limit건 반환합니다.
     * 실제 점유 판정은 호출부가 아니라 analyzeAsync 안의 tryClaim이 하므로, 여기서 고른 건이 모두 처리되지는 않습니다.
     * 역직렬화에 실패한 건은 복원할 수 없어 조용히 제외되며, 그 행은 attemptCount가 오르지 않아 계속 조회에 걸립니다.
     */
    public List<CommentEvent> findRetryTargets(int limit) {
        Instant retryableBefore = Instant.now().minus(notifyRetryDelay());
        List<CommentEvent> events = new ArrayList<>();
        for (CommentAnalysisState state : repository.findRetryTargets(
                retryableBefore, properties.getRetryMaxAttempts(), PageRequest.of(0, limit))) {
            CommentEvent event = deserializeEvent(state);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    // 기동 시 잔존 IN_PROGRESS를 FAILED로 강등해 재시도 대상으로 만든다 - 강등된 행 수를 반환
    public int demoteStaleInProgress() {
        return repository.demoteAllInProgress(Instant.now());
    }

    private CommentEvent deserializeEvent(CommentAnalysisState state) {
        try {
            return objectMapper.readValue(state.getEventJson(), CommentEvent.class);
        } catch (Exception e) {
            log.warn("저장된 이벤트 역직렬화 실패, 재시도 대상에서 제외: comment={}", state.getCommentId(), e);
            return null;
        }
    }

    // 이 코멘트를 분석한 적이 있는지(진행 중/실패 포함) 확인 — 삭제 알림 대상 판별용
    public boolean isTracked(long commentId) {
        return repository.existsById(commentId);
    }
}
