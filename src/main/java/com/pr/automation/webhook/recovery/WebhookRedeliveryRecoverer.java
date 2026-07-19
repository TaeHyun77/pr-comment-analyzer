package com.pr.automation.webhook.recovery;

import com.pr.automation.config.GithubProperties;
import com.pr.automation.github.GithubDeliveryClient;
import com.pr.automation.github.GithubDeliveryClient.GhDelivery;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** 부팅 시 1회 자동 실행 + 수동 API 호출 가능한 웹훅 복구 진입점
 * GitHub deliveries 응답이 delivered_at 내림차순이라는 사실에 의존, cutoff 만나면 즉시 break해 페이지네이션을 회피함
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookRedeliveryRecoverer {
    private static final int DEFAULT_LOOKBACK_HOURS = 24;

    private final GithubProperties githubProperties;
    private final GithubDeliveryClient deliveryClient;
    private final DeliveryStore deliveryStore;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        GithubProperties.WebhookRecovery cfg = githubProperties.getWebhookRecovery();
        if (cfg == null || !cfg.isEnabled()) {
            log.info("웹훅 복구 비활성화됨, 부팅 훅 건너뜀");
            return;
        }
        int hours = cfg.getLookbackHours() > 0 ? cfg.getLookbackHours() : DEFAULT_LOOKBACK_HOURS;
        try {
            recoverNow(hours);
        } catch (Exception e) {
            log.warn("웹훅 복구 부팅 훅 실패", e);
        }
    }

    // 운영 중 사각지대(큐 포화, 강제 종료, Slack 장애, 네트워크 단절) 회복용 정기 스케줄링
    // 첫 발화는 interval 후 — 부팅 훅과 시점이 겹치지 않게 함
    @Scheduled(
            fixedDelayString = "${github.webhook-recovery.scheduler-interval-ms:1800000}",
            initialDelayString = "${github.webhook-recovery.scheduler-interval-ms:1800000}"
    )
    public void scheduledRecover() {
        GithubProperties.WebhookRecovery cfg = githubProperties.getWebhookRecovery();
        if (cfg == null || !cfg.isEnabled() || !cfg.isSchedulerEnabled()) {
            return;
        }
        int hours = cfg.getSchedulerLookbackHours() > 0 ? cfg.getSchedulerLookbackHours() : 1;
        try {
            recoverNow(hours);
        } catch (Exception e) {
            log.warn("스케줄 웹훅 복구 실패", e);
        }
    }

    public RecoverySummary recoverNow(int lookbackHours) {
        int effectiveHours = lookbackHours > 0 ? lookbackHours : DEFAULT_LOOKBACK_HOURS;
        List<RepoResult> results = new ArrayList<>();

        if (!deliveryClient.isEnabled()) {
            log.info("GitHub 토큰 미설정으로 웹훅 복구 건너뜀");
            return new RecoverySummary(effectiveHours, results, 0, 0);
        }
        List<String> repos = githubProperties.getRepos();
        if (repos == null || repos.isEmpty()) {
            log.info("github.repos 미설정으로 웹훅 복구 건너뜀");
            return new RecoverySummary(effectiveHours, results, 0, 0);
        }

        int maxRedeliverAttempts = githubProperties.getWebhookRecovery().getMaxRedeliverAttempts();
        Instant cutoff = Instant.now().minus(effectiveHours, ChronoUnit.HOURS);
        int totalTriggered = 0;
        int totalSkipped = 0;
        for (String repo : repos) {
            RepoResult r = recoverRepo(repo, cutoff, maxRedeliverAttempts);
            results.add(r);
            totalTriggered += r.getTriggered();
            totalSkipped += r.getSkipped();
        }
        log.info("웹훅 복구 전체 완료: 기간={}h triggered={} skipped={}", effectiveHours, totalTriggered, totalSkipped);
        return new RecoverySummary(effectiveHours, results, totalTriggered, totalSkipped);
    }

    private RepoResult recoverRepo(String repo, Instant cutoff, int maxRedeliverAttempts) {
        try {
            Optional<Long> hookOpt = deliveryClient.findHookId(repo);
            if (!hookOpt.isPresent()) {
                log.warn("hook 미발견, 복구 건너뜀: {}", repo);
                return new RepoResult(repo, null, 0, 0, "hook 미발견");
            }
            long hookId = hookOpt.get();

            Optional<List<GhDelivery>> listOpt = deliveryClient.listDeliveries(repo, hookId);
            if (!listOpt.isPresent()) {
                log.warn("delivery 조회 실패, 복구 건너뜀: repo={} hook={}", repo, hookId);
                return new RepoResult(repo, hookId, 0, 0, "delivery 조회 실패");
            }

            int triggered = 0;
            int skipped = 0;
            for (GhDelivery d : listOpt.get()) {
                if (d.getDeliveredAt() == null) {
                    continue;
                }
                if (d.getDeliveredAt().isBefore(cutoff)) {
                    break; // 내림차순이므로 이후는 모두 더 오래된 항목
                }
                if (deliveryStore.isReceived(d.getGuid())) {
                    skipped++;
                    continue;
                }
                if (deliveryStore.getRedeliverCount(d.getGuid()) >= maxRedeliverAttempts) {
                    log.warn("재전송 상한({}회) 초과, 복구 포기 (포이즌 delivery): repo={} guid={}",
                            maxRedeliverAttempts, repo, d.getGuid());
                    skipped++;
                    continue;
                }
                if (d.getId() != null && deliveryClient.redeliver(repo, hookId, d.getId())) {
                    deliveryStore.recordRedeliverAttempt(d.getGuid());
                    triggered++;
                }
            }
            log.info("웹훅 복구 완료: repo={} hook={} triggered={} skipped={}",
                    repo, hookId, triggered, skipped);
            return new RepoResult(repo, hookId, triggered, skipped, null);
        } catch (Exception e) {
            log.warn("웹훅 복구 실패, 다음 레포로 진행: {}", repo, e);
            return new RepoResult(repo, null, 0, 0, e.getMessage());
        }
    }

    @Getter
    @AllArgsConstructor
    public static class RecoverySummary {
        private final int lookbackHours;
        private final List<RepoResult> repos;
        private final int totalTriggered;
        private final int totalSkipped;

        public static RecoverySummary empty(int lookbackHours) {
            return new RecoverySummary(lookbackHours, Collections.emptyList(), 0, 0);
        }
    }

    @Getter
    @AllArgsConstructor
    public static class RepoResult {
        private final String repoFullName;
        private final Long hookId;
        private final int triggered;
        private final int skipped;
        private final String error; // null이면 성공
    }
}
