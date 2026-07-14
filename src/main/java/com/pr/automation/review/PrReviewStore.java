package com.pr.automation.review;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

// 이미 리뷰한 PR 키(repo#pr)를 기억해 중복 리뷰(웹훅 재전송 포함)를 막습니다.
// CommentStore와 동일한 패턴 - 키 타입만 String
@Slf4j
@Component
@RequiredArgsConstructor
public class PrReviewStore {
    // 점유 후 이 시간 동안 완료/실패가 없으면 죽은 워커의 점유로 간주해 탈취를 허용
    static final Duration LEASE_TIMEOUT = Duration.ofMinutes(15);

    private final PrReviewStateRepository repository;

    // 이미 처리 완료된 키거나 다른 워커가 점유 중이면 false, 점유에 성공하면 true를 반환
    public boolean tryClaim(String prKey) {
        try {
            repository.saveAndFlush(PrReviewState.claim(prKey, Instant.now()));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 이미 행이 있음 — 만료된 lease(죽은 워커의 점유)면 탈취, 아니면(진행 중/완료) 포기
            Instant now = Instant.now();
            boolean reclaimed = repository.reclaimExpired(prKey, now, now.minus(LEASE_TIMEOUT)) == 1;
            if (reclaimed) {
                log.warn("만료된 점유 탈취(lease {}분 초과): pr={}", LEASE_TIMEOUT.toMinutes(), prKey);
            }
            return reclaimed;
        }
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
