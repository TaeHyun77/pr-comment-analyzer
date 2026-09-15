package com.pr.automation.slack;

import com.pr.automation.analysis.comment.dto.AnalysisResult;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import com.pr.automation.llm.dto.LlmUsage;
import com.pr.automation.config.properties.SlackProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

// 분석 결과를 Slack Incoming Webhook으로 보냅니다. slack.enabled=false면 전송 없이 로그만 남김
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackNotifier {
    private static final int TEXT_LIMIT = 2_900;
    private static final int HEADER_LIMIT = 150;
    private static final int REPLY_LIMIT = 2_700;
    // 일시 오류 재시도 시작 백오프 — Slack 순단은 대부분 수 초 내 회복되므로 1s→2s로 짧게 잡음.
    // 재시도 횟수(slackProperties.maxAttempts)는 env로 분리, 소진 후 실패는 호출자와 복구 사이클 몫
    private static final long INITIAL_BACKOFF_MS = 1000L;

    private final RestTemplate slackRestTemplate;
    private final SlackProperties slackProperties;

    public void send(CommentEvent event, AnalysisResult result) {
        if (!slackProperties.isEnabled() || !StringUtils.hasText(slackProperties.getWebhookUrl())) {
            log.info("Slack 비활성화/미설정 — 분석 결과 통지 생략: {} #{} comment={}",
                    event.getRepoFullName(), event.getPrNumber(), event.getCommentId());
            return;
        }

        post(buildPayload(event, result));

        // 성공을 남기지 않으면 통지 여부를 예외가 없었다는 사실로만 추론해야 한다
        log.info("분석 결과 통지 완료: {} #{} comment={}",
                event.getRepoFullName(), event.getPrNumber(), event.getCommentId());
    }

    /**
     * 분석했던(또는 분석 중이던) 코멘트가 삭제됐음을 알립니다.
     * 이미 통지한 분석의 원본이 사라졌다는 사실만 전하면 되므로 분석 결과는 싣지 않습니다.
     */
    public void sendCommentDeleted(String repoFullName, int prNumber, long commentId, String commentHtmlUrl) {
        if (!slackProperties.isEnabled() || !StringUtils.hasText(slackProperties.getWebhookUrl())) {
            log.info("Slack 비활성화/미설정 — 코멘트 삭제 알림 생략: {} #{} comment={}", repoFullName, prNumber, commentId);
            return;
        }
        String text = "분석했던 코멘트가 삭제됨: " + repoFullName + " #" + prNumber;

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(section(":wastebasket: *분석했던 코멘트가 삭제됨*\n"
                + repoFullName + " #" + prNumber + "\n앞서 보낸 분석의 원본 코멘트가 GitHub에서 삭제됐습니다."));
        if (StringUtils.hasText(commentHtmlUrl)) {
            blocks.add(actionBlock(commentHtmlUrl, "🔗 PR 열기"));
        }
        try {
            post(mapOf("text", text, "blocks", blocks));
        } catch (RuntimeException e) {
            // 알림 실패가 웹훅 ack를 막으면 복구 사이클이 삭제 이벤트를 계속 재전송하게 되므로 삼킨다
            log.warn("코멘트 삭제 알림 전송 실패: {} #{} comment={}", repoFullName, prNumber, commentId, e);
        }
    }

    /**
     * 자동 복구를 포기한 코멘트를 알립니다. 이후 스케줄러가 다시 집지 않으므로 사람이 확인해야 합니다.
     * 상한에 닿는 순간 복구 대상에서 빠지므로 이 알림은 코멘트당 한 번만 발생합니다.
     */
    public void sendRecoveryAbandoned(String repoFullName, int prNumber, long commentId, int attempts, Throwable lastError) {
        if (!slackProperties.isEnabled() || !StringUtils.hasText(slackProperties.getWebhookUrl())) {
            log.info("Slack 비활성화/미설정 — 복구 포기 알림 생략: {} #{} comment={}", repoFullName, prNumber, commentId);
            return;
        }
        String errorSummary = lastError == null
                ? "원인 미상"
                : lastError.getClass().getSimpleName() + ": " + abbreviate(lastError.getMessage(), 300);
        String text = "코멘트 분석 자동 복구 포기: " + repoFullName + " #" + prNumber + " — " + errorSummary;

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(section(":no_entry: *코멘트 분석 자동 복구 포기*\n"
                + repoFullName + " #" + prNumber + " (comment " + commentId + ")\n"
                + attempts + "회 재시도 실패로 자동 복구를 중단했습니다. 수동 확인이 필요합니다.\n`" + errorSummary + "`"));
        blocks.add(actionBlock("https://github.com/" + repoFullName + "/pull/" + prNumber, "🔗 PR 열기"));
        try {
            post(mapOf("text", text, "blocks", blocks));
        } catch (RuntimeException e) {
            log.warn("복구 포기 알림 전송도 실패: {} #{} comment={}", repoFullName, prNumber, commentId, e);
        }
    }

    // 통지 실패
    public void sendFailure(CommentEvent event, Throwable error) {
        if (!slackProperties.isEnabled() || !StringUtils.hasText(slackProperties.getWebhookUrl())) {
            // 분석 실패가 이 알림 외에는 드러나지 않으므로, 알림을 건너뛰었다는 사실은 반드시 남긴다
            log.info("Slack 비활성화/미설정 — 분석 실패 알림 생략: {} #{} comment={}",
                    event.getRepoFullName(), event.getPrNumber(), event.getCommentId());
            return;
        }

        String errorSummary = error.getClass().getSimpleName() + ": " + abbreviate(error.getMessage(), 300);
        String text = "코멘트 분석 실패: " + event.getRepoFullName() + " #" + event.getPrNumber() + " — " + errorSummary;

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(section(":warning: *코멘트 분석 실패*\n"
                + event.getRepoFullName() + " #" + event.getPrNumber() + "\n`" + errorSummary + "`"));
        if (StringUtils.hasText(event.getCommentHtmlUrl())) {
            blocks.add(actionBlock(event.getCommentHtmlUrl(), "💬 GitHub에서 답변하기"));
        }
        try {
            post(mapOf("text", text, "blocks", blocks));
            log.info("분석 실패 알림 전송 완료: {} #{} comment={}",
                    event.getRepoFullName(), event.getPrNumber(), event.getCommentId());
        } catch (RuntimeException e) {
            log.warn("Slack 실패 알림 전송도 실패", e);
        }
    }

    // 알림에서 코멘트 출처를 구분하기 위한 표기
    private static String commentKindLabel(CommentEvent e) {
        if (e.isReviewComment()) {
            return "인라인 리뷰 코멘트";
        }
        if (e.isReviewBody()) {
            return StringUtils.hasText(e.getReviewState())
                    ? "리뷰 총평 (" + e.getReviewState() + ")"
                    : "리뷰 총평";
        }
        if (e.isCommitComment()) {
            return "커밋 코멘트";
        }
        return "PR 일반 코멘트";
    }

    Map<String, Object> buildPayload(CommentEvent e, AnalysisResult r) {
        // 파일 전체 대상 코멘트는 line이 1로 채워져 오므로 줄 번호를 붙이면 첫 줄 지적으로 오독된다
        boolean showLine = e.getLine() != null && !e.isFileLevel();
        String location = StringUtils.hasText(e.getFilePath())
                ? " · " + e.getFilePath() + (showLine ? ":" + e.getLine() : "")
                : "";
        String header = "🔍 " + e.getRepoFullName() + " #" + e.getPrNumber() + location;

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(mapOf(
                "type", "header",
                "text", mapOf("type", "plain_text", "text", abbreviate(header, HEADER_LIMIT), "emoji", true)));
        blocks.add(section("*코멘트 요약*\n" + nv(r.getCommentSummary())));
        blocks.add(mapOf(
                "type", "section",
                "fields", Arrays.asList(
                        mapOf("type", "mrkdwn", "text", truncate("*현재 방식*\n" + nv(r.getCurrentApproach()), TEXT_LIMIT)),
                        mapOf("type", "mrkdwn", "text", truncate("*제안 방식*\n" + nv(r.getSuggestedApproach()), TEXT_LIMIT)))));
        blocks.add(section("*판정*\n" + nv(r.getVerdict())));
        blocks.add(section("*근거*\n" + nv(r.getReasoning())));
        if (StringUtils.hasText(r.getSuggestedReply())) {
            blocks.add(section("*제안 답변*\n```" + truncate(r.getSuggestedReply(), REPLY_LIMIT) + "```"));
        }
        if (StringUtils.hasText(e.getCommentHtmlUrl())) {
            blocks.add(actionBlock(e.getCommentHtmlUrl(), "💬 GitHub에서 답변하기"));
        }
        List<Map<String, Object>> contextElements = new ArrayList<>();
        contextElements.add(mapOf(
                "type", "mrkdwn",
                "text", "작성자 `" + nv(e.getCommentAuthor()) + "` · "
                        + commentKindLabel(e)));

        String usage = formatUsage(r);
        if (usage != null) {
            contextElements.add(mapOf("type", "mrkdwn", "text", usage));
        }
        blocks.add(mapOf("type", "context", "elements", contextElements));

        return mapOf(
                "text", "PR #" + e.getPrNumber() + " 코멘트 분석: " + abbreviate(nv(r.getVerdict()), HEADER_LIMIT),
                "blocks", blocks);
    }

    // 일시 오류(429/5xx/네트워크)는 재시도하고, 소진 시 예외를 호출자에게 전달
    private void post(Map<String, Object> payload) {
        int maxAttempts = slackProperties.getMaxAttempts();
        long backoffMillis = INITIAL_BACKOFF_MS;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String response = slackRestTemplate.postForObject(slackProperties.getWebhookUrl(), payload, String.class);
                if (response != null && !"ok".equalsIgnoreCase(response.trim())) {
                    log.warn("Slack 응답이 ok가 아님: {}", response);
                }
                return;
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS && !e.getStatusCode().is5xxServerError()) {
                    // 잘못된 payload 등 영구 오류 — 재시도해도 결과가 같으므로 즉시 실패
                    throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.SLACK_API_ERROR, e);
                }
                if (attempt == maxAttempts) {
                    throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.SLACK_API_ERROR, e);
                }
                log.warn("Slack 전송 일시 오류({}), 재시도 {}/{}", e.getStatusCode(), attempt, maxAttempts);
            } catch (ResourceAccessException e) {
                if (attempt == maxAttempts) {
                    throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.SLACK_API_ERROR, e);
                }
                log.warn("Slack 전송 네트워크 오류({}), 재시도 {}/{}", e.getMessage(), attempt, maxAttempts);
            } catch (RestClientException e) {
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.SLACK_API_ERROR, e);
            }
            if (!sleepWithFullJitter(backoffMillis)) {
                // 인터럽트(종료 시그널) — 남은 재시도를 포기하고 실패로 처리
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.SLACK_API_ERROR, "Slack 전송 재시도 중 인터럽트 발생");
            }
            backoffMillis *= 2;
        }
    }

    protected boolean sleepWithFullJitter(long maxMillis) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(maxMillis + 1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // 사용량은 토큰을 기준으로 표기한다 - 비용은 모델과 요금제에 따라 변하는 파생값이라 추세 비교가 어렵다
    private static String formatUsage(AnalysisResult r) {
        LlmUsage u = r.getUsage();
        return u == null ? null : "토큰 " + u.getTotalTokens();
    }

    private static Map<String, Object> section(String mrkdwn) {
        return mapOf("type", "section", "text", mapOf("type", "mrkdwn", "text", truncate(mrkdwn, TEXT_LIMIT)));
    }

    // URL 버튼 1개를 가진 actions 블록을 만든다. 인바운드 webhook 불필요한 link 타입 버튼.
    private static Map<String, Object> actionBlock(String url, String label) {
        return mapOf(
                "type", "actions",
                "elements", Collections.singletonList(mapOf(
                        "type", "button",
                        "text", mapOf("type", "plain_text", "text", label, "emoji", true),
                        "url", url,
                        "style", "primary")));
    }

    private static Map<String, Object> mapOf(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("키/값이 쌍이 아닙니다");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String nv(String s) {
        return StringUtils.hasText(s) ? s : "—";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "—";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String abbreviate(String s, int max) {
        return s == null ? "" : truncate(s, max);
    }
}
