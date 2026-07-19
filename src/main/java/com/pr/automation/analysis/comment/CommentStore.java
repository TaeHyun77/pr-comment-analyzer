package com.pr.automation.analysis.comment;

import com.pr.automation.common.entity.WorkStatus;
import com.pr.automation.config.PrAnalyzerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// 코멘트 중복 처리 방지 컴포넌트 — DB의 PK 제약으로 확인-등록을 원자화해 다중 인스턴스에서도 하나만 점유
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentStore {
    private final CommentAnalysisStateRepository repository;
    private final PrAnalyzerProperties properties;

    // 점유 후 이 시간 동안 완료/실패가 없으면 죽은 워커의 점유로 간주해 탈취를 허용.
    // 값 근거는 pr-analyzer.lease-timeout-minutes(PrAnalyzerProperties) 주석 참조 — 분석 단계 최악 지연을 넘겨야 함
    Duration leaseTimeout() {
        return Duration.ofMinutes(properties.getLeaseTimeoutMinutes());
    }

    // 해당 ID가 이미 처리 완료됐거나 다른 워커가 점유 중이면 false, 점유에 성공하면 true를 반환
    public boolean tryClaim(long commentId) {
        try {
            repository.saveAndFlush(CommentAnalysisState.claim(commentId, Instant.now()));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 이미 행이 있음 — 만료된 lease(죽은 워커의 점유)면 탈취, 아니면(진행 중/완료) 포기
            Instant now = Instant.now();
            boolean reclaimed = repository.reclaimExpired(commentId, now, now.minus(leaseTimeout())) == 1;
            if (reclaimed) {
                log.warn("만료된 점유 탈취(lease {}분 초과): comment={}", leaseTimeout().toMinutes(), commentId);
            }
            return reclaimed;
        }
    }

    // 분석 결과(JSON)를 저장하고 통지 대기(ANALYZED) 상태로 전환
    // 리포지토리 메서드가 자체 트랜잭션으로 즉시 커밋하는 것이 전제 — 호출 경로에 @Transactional을 걸면
    // 커밋이 통지 이후로 밀려 "통지 실패 시 결과 보존" 보장이 깨진다
    public void markAnalyzed(long commentId, String resultJson) {
        repository.markAnalyzed(commentId, resultJson, Instant.now());
    }

    // ANALYZED 상태(통지 대기)인 행의 저장된 결과 JSON을 반환. 그 외 상태면 empty
    public Optional<String> findAnalyzedResult(long commentId) {
        return repository.findById(commentId)
                .filter(s -> s.getStatus() == WorkStatus.ANALYZED)
                .map(CommentAnalysisState::getResultJson);
    }

    // 점유를 완료 상태로 전환 — 이후 같은 ID의 tryClaim은 항상 false
    public void markCompleted(long commentId) {
        repository.complete(commentId, Instant.now());
    }

    // 분석 실패 시 점유 행을 삭제해 같은 ID의 재시도를 허용
    public void release(long commentId) {
        repository.releaseClaim(commentId);
    }
}
