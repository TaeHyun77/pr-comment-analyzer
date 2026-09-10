package com.pr.automation.analysis.comment;

import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 처리가 끝나지 않은 코멘트 분석을 다시 실행하는 복구 스케줄러
 * 대상 선별 기준과 재분석/재통지 분기는 각각 CommentStore와 CommentAnalysisService가 이미 갖고 있어,
 * 여기서는 고른 이벤트를 그대로 다시 던지기만 합니다. 실제 점유 판정은 analyzeAsync 안의 tryClaim이 합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentRetryScheduler {

    private final CommentStore commentStore;
    private final CommentAnalysisService analysisService;
    private final CommentAnalyzerProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void demoteStaleClaimsOnStartup() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            int demoted = commentStore.demoteStaleInProgress();
            if (demoted > 0) {
                log.warn("기동 시 잔존 점유 {}건을 FAILED로 강등 — 다음 재시도 주기에 복구됨", demoted);
            }
        } catch (Exception e) {
            log.error("기동 시 잔존 점유 강등 실패 — 해당 건은 다음 기동까지 복구되지 않음", e);
        }
    }

    // 첫 발화를 주기만큼 미뤄 기동 훅의 강등이 끝난 뒤에 돌게 한다 —
    // @Scheduled는 ApplicationReadyEvent보다 먼저 시작될 수 있어 순서를 보장하지 않으면 한 주기를 헛돈다
    @Scheduled(
            fixedDelayString = "${comment-analyzer.retry-interval-ms}",
            initialDelayString = "${comment-analyzer.retry-interval-ms}"
    )
    public void retryPending() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            List<CommentEvent> targets = commentStore.findRetryTargets(properties.getRetryBatchSize());
            if (targets.isEmpty()) {
                return;
            }

            log.info("코멘트 분석 재시도 {}건 실행", targets.size());
            for (CommentEvent event : targets) {
                analysisService.analyzeAsync(event);
            }
        } catch (Exception e) {
            // 한 사이클의 실패로 스케줄이 멈추지 않게 삼킨다 — 다음 주기에 다시 시도한다
            log.error("코멘트 분석 재시도 사이클 실패", e);
        }
    }
}
