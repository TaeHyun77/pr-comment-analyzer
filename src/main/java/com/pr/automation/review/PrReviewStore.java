package com.pr.automation.review;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.pr.automation.common.entity.WorkStatus;
import com.pr.automation.config.PrReviewProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// 이미 리뷰한 PR 키(repo#pr)를 기억해 중복 리뷰(웹훅 재전송 포함)를 막습니다.
// CommentStore와 동일한 패턴 - 키 타입만 String
@Slf4j
@Component
@RequiredArgsConstructor
public class PrReviewStore {
    private final PrReviewStateRepository repository;
    private final PrReviewProperties properties;

    // 점유 후 이 시간 동안 완료/실패가 없으면 죽은 워커의 점유로 간주해 탈취를 허용.
    // 값 근거는 pr-review.lease-timeout-minutes(PrReviewProperties) 주석 참조 — 분석 단계 최악 지연을 넘겨야 함
    Duration leaseTimeout() {
        return Duration.ofMinutes(properties.getLeaseTimeoutMinutes());
    }

    // 이미 처리 완료된 키거나 다른 워커가 점유 중이면 false, 점유에 성공하면 true를 반환
    public boolean tryClaim(String prKey) {
        try {
            repository.saveAndFlush(PrReviewState.claim(prKey, Instant.now()));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 이미 행이 있음 - 만료된 lease면 탈취하고, 아니라면(진행 중/완료) 포기
            Instant now = Instant.now();
            boolean reclaimed = repository.reclaimExpired(prKey, now, now.minus(leaseTimeout())) == 1;
            if (reclaimed) {
                log.warn("만료된 점유 탈취(lease {}분 초과): pr={}", leaseTimeout().toMinutes(), prKey);
            }
            return reclaimed;
        }
    }

    // 리뷰 결과(JSON)를 저장하고 게시 대기(ANALYZED) 상태로 전환
    // 리포지토리 메서드가 자체 트랜잭션으로 즉시 커밋하는 것이 전제 - 호출 경로에 @Transactional을 걸면 게시 실패 시 결과 보존 보장이 깨지게 됩니다.
    public void markAnalyzed(String prKey, String resultJson) {
        repository.markAnalyzed(prKey, resultJson, Instant.now());
    }

    // ANALYZED 상태(게시 대기)인 행의 저장된 결과 JSON을 반환. 그 외 상태면 empty
    public Optional<String> findAnalyzedResult(String prKey) {
        return repository.findById(prKey)
                .filter(s -> s.getStatus() == WorkStatus.ANALYZED)
                .map(PrReviewState::getResultJson);
    }

    // 점유를 완료 상태로 전환 — 이후 같은 키의 tryClaim은 항상 false
    public void markCompleted(String prKey) {
        repository.complete(prKey, Instant.now());
    }

    // 리뷰 실패 시 점유 행을 삭제해 같은 키의 재시도를 허용
    public void release(String prKey) {
        repository.releaseClaim(prKey);
    }
}
