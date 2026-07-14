package com.pr.automation.webhook.recovery;

import com.pr.automation.config.GithubProperties;
import com.pr.automation.github.GithubDeliveryClient;
import com.pr.automation.github.GithubDeliveryClient.GhDelivery;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// 실제 발생한 PR 코멘트 수 대비, 우리 시스템이 ack 처리한 코멘트 수를 비교하는 클래스
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverageReporter {
    private static final int DEFAULT_LOOKBACK_HOURS = 24;
    private static final String CREATED_ACTION = "created";
    private static final Set<String> PR_COMMENT_EVENTS = new HashSet<>(Arrays.asList(
            "issue_comment", "pull_request_review_comment"));

    private final GithubProperties githubProperties;
    private final GithubDeliveryClient deliveryClient;
    private final DeliveryStore deliveryStore;

    // 설정된 각 저장소를 순회하며 reportRepo()로 개별 커버리지를 구하고, 전체 합계를 로그로 남긴 뒤 CoverageReport를 반환
    public CoverageReport report(int lookbackHours) {
        int effectiveHours = lookbackHours > 0 ? lookbackHours : DEFAULT_LOOKBACK_HOURS;
        List<RepoCoverage> per = new ArrayList<>();

        if (!deliveryClient.isEnabled()) {
            log.info("GitHub 토큰 미설정으로 커버리지 조회 불가");
            return new CoverageReport(effectiveHours, per, 0, 0);
        }
        List<String> repos = githubProperties.getRepos();
        if (repos == null || repos.isEmpty()) {
            log.info("github.repos 미설정으로 커버리지 조회 불가");
            return new CoverageReport(effectiveHours, per, 0, 0);
        }

        Instant cutoff = Instant.now().minus(effectiveHours, ChronoUnit.HOURS);
        int sumTotal = 0;
        int sumProcessed = 0;
        for (String repo : repos) {
            RepoCoverage rc = reportRepo(repo, cutoff);
            per.add(rc);
            sumTotal += rc.getTotalComments();
            sumProcessed += rc.getProcessedComments();
            log.info("코멘트 커버리지 [repo={} 기간={}h] 처리 {}/{} (누락 {})",
                    repo, effectiveHours, rc.getProcessedComments(), rc.getTotalComments(),
                    rc.getTotalComments() - rc.getProcessedComments());
        }
        log.info("코멘트 커버리지 [전체 기간={}h] 처리 {}/{} (누락 {})",
                effectiveHours, sumProcessed, sumTotal, sumTotal - sumProcessed);
        return new CoverageReport(effectiveHours, per, sumTotal, sumProcessed);
    }

    private RepoCoverage reportRepo(String repo, Instant cutoff) {
        try {
            Optional<Long> hookOpt = deliveryClient.findHookId(repo);
            if (!hookOpt.isPresent()) {
                return new RepoCoverage(repo, 0, 0, Collections.emptyList());
            }
            long hookId = hookOpt.get();
            Optional<List<GhDelivery>> listOpt = deliveryClient.listDeliveries(repo, hookId);
            if (!listOpt.isPresent()) {
                return new RepoCoverage(repo, 0, 0, Collections.emptyList());
            }
            int total = 0;
            int processed = 0;
            List<String> missing = new ArrayList<>();
            for (GhDelivery d : listOpt.get()) {
                if (d.getDeliveredAt() == null) {
                    continue;
                }
                if (d.getDeliveredAt().isBefore(cutoff)) {
                    break; // 내림차순이므로 이후는 모두 더 오래된 항목
                }
                if (d.getEvent() == null || !PR_COMMENT_EVENTS.contains(d.getEvent())) {
                    continue;
                }
                if (!CREATED_ACTION.equals(d.getAction())) {
                    continue;
                }
                total++;
                if (deliveryStore.isReceived(d.getGuid())) {
                    processed++;
                } else {
                    missing.add(d.getGuid());
                }
            }
            return new RepoCoverage(repo, total, processed, missing);
        } catch (Exception e) {
            log.warn("커버리지 조회 실패, 0건으로 처리: {}", repo, e);
            return new RepoCoverage(repo, 0, 0, Collections.emptyList());
        }
    }

    @Getter
    @AllArgsConstructor
    public static class CoverageReport {
        private final int lookbackHours;
        private final List<RepoCoverage> repos;
        private final int totalComments;
        private final int processedComments;
    }

    @Getter
    @AllArgsConstructor
    public static class RepoCoverage {
        private final String repoFullName;
        private final int totalComments;
        private final int processedComments;
        private final List<String> missingGuids;
    }
}
