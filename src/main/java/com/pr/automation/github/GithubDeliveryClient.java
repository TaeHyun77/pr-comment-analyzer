package com.pr.automation.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pr.automation.config.GithubProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 웹훅 delivery 조회 + 재전송 전용 GitHub API 클라이언트
 * 기존 GithubClient와 동일하게 토큰 없으면 비활성, 실패는 swallowing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubDeliveryClient {
    private static final String EVENT_ISSUE_COMMENT = "issue_comment";
    private static final String EVENT_REVIEW_COMMENT = "pull_request_review_comment";

    private final RestTemplate githubRestTemplate;
    private final GithubProperties githubProperties;

    public boolean isEnabled() {
        return StringUtils.hasText(githubProperties.getToken());
    }

    // 레포에 등록된 active webhook 중 issue_comment 또는 pull_request_review_comment를
    // 구독하는 첫 번째 hook 반환. 여러 개면 WARN 후 첫 번째
    public Optional<Long> findHookId(String repoFullName) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            return Optional.empty();
        }
        try {
            GhHookSummary[] hooks = githubRestTemplate.getForObject(
                    "/repos/{owner}/{repo}/hooks",
                    GhHookSummary[].class,
                    parts[0], parts[1]);
            if (hooks == null || hooks.length == 0) {
                log.info("등록된 webhook 없음: {}", repoFullName);
                return Optional.empty();
            }
            List<GhHookSummary> matching = new ArrayList<>();
            for (GhHookSummary h : hooks) {
                if (Boolean.TRUE.equals(h.getActive())
                        && h.getEvents() != null
                        && (h.getEvents().contains(EVENT_ISSUE_COMMENT)
                            || h.getEvents().contains(EVENT_REVIEW_COMMENT))) {
                    matching.add(h);
                }
            }
            if (matching.isEmpty()) {
                log.info("코멘트 이벤트를 구독하는 active webhook 없음: {}", repoFullName);
                return Optional.empty();
            }
            if (matching.size() > 1) {
                log.warn("여러 active webhook 발견, 첫 번째 사용: repo={} count={}",
                        repoFullName, matching.size());
            }
            return Optional.ofNullable(matching.get(0).getId());
        } catch (Exception e) {
            log.warn("GitHub hook 조회 실패: {}", repoFullName, e);
            return Optional.empty();
        }
    }

    // hook의 최근 delivery 목록
    public Optional<List<GhDelivery>> listDeliveries(String repoFullName, long hookId) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            return Optional.empty();
        }
        try {
            GhDelivery[] arr = githubRestTemplate.getForObject(
                    "/repos/{owner}/{repo}/hooks/{hookId}/deliveries?per_page=100",
                    GhDelivery[].class,
                    parts[0], parts[1], hookId);
            if (arr == null) {
                return Optional.of(Collections.emptyList());
            }
            return Optional.of(Arrays.asList(arr));
        } catch (Exception e) {
            log.warn("GitHub deliveries 조회 실패: repo={} hook={}", repoFullName, hookId, e);
            return Optional.empty();
        }
    }

    // delivery 재전송, 2xx면 true
    public boolean redeliver(String repoFullName, long hookId, long deliveryId) {
        if (!isEnabled()) {
            return false;
        }
        String[] parts = splitRepo(repoFullName);
        if (parts == null) {
            return false;
        }
        try {
            githubRestTemplate.exchange(
                    "/repos/{owner}/{repo}/hooks/{hookId}/deliveries/{deliveryId}/attempts",
                    HttpMethod.POST,
                    HttpEntity.EMPTY,
                    Void.class,
                    parts[0], parts[1], hookId, deliveryId);
            return true;
        } catch (Exception e) {
            log.warn("GitHub redelivery 요청 실패: repo={} hook={} delivery={}",
                    repoFullName, hookId, deliveryId, e);
            return false;
        }
    }

    private static String[] splitRepo(String repoFullName) {
        if (!StringUtils.hasText(repoFullName)) {
            return null;
        }
        String[] parts = repoFullName.split("/", 2);
        return parts.length == 2 ? parts : null;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class GhHookSummary {
        private Long id;
        private Boolean active;
        private List<String> events;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class GhDelivery {
        private Long id;             // redelivery API URL용 정수 ID
        private String guid;         // X-GitHub-Delivery 헤더 값 — ledger 키
        @JsonProperty("delivered_at")
        private Instant deliveredAt;
        private String status;
        private String event;
        private String action;
    }
}
