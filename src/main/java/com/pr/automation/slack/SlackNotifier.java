package com.pr.automation.slack;

import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.SlackProperties;
import com.pr.automation.review.dto.PrReviewEvent;
import com.pr.automation.review.dto.PrReviewResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 분석 결과를 Slack Incoming Webhook으로 보냄, slack.enabled=false면 전송 없이 로그만 남김
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackNotifier {
    private static final int TEXT_LIMIT = 2_900;
    private static final int HEADER_LIMIT = 150;
    private static final int REPLY_LIMIT = 2_700;

    private final RestTemplate slackRestTemplate;
    private final SlackProperties slackProperties;

    public void send(CommentEvent event, AnalysisResult result) {
        if (!slackProperties.isEnabled()) {
            log.info("Slack 비활성화 — {} #{} verdict='{}' summary='{}'", event.getRepoFullName(), event.getPrNumber(), result.getVerdict(), result.getCommentSummary());
            return;
        }
        if (!StringUtils.hasText(slackProperties.getWebhookUrl())) {
            log.warn("slack.webhook-url 미설정 — 전송 생략");
            return;
        }
        post(buildPayload(event, result));
    }

    public void sendFailure(CommentEvent event, Throwable error) {
        if (!slackProperties.isEnabled() || !StringUtils.hasText(slackProperties.getWebhookUrl())) {
            return;
        }
        String errorSummary = error.getClass().getSimpleName() + ": " + abbreviate(error.getMessage(), 300);
        // text는 알림 미리보기/푸시용 fallback. UI는 blocks를 우선 렌더링한다.
        String text = "코멘트 분석 실패: " + event.getRepoFullName() + " #" + event.getPrNumber() + " — " + errorSummary;

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(section(":warning: *코멘트 분석 실패*\n"
                + event.getRepoFullName() + " #" + event.getPrNumber() + "\n`" + errorSummary + "`"));
        if (StringUtils.hasText(event.getCommentHtmlUrl())) {
            blocks.add(actionBlock(event.getCommentHtmlUrl(), "💬 GitHub에서 답변하기"));
        }
        try {
            post(mapOf("text", text, "blocks", blocks));
        } catch (RuntimeException e) {
            log.warn("Slack 실패 알림 전송도 실패", e);
        }
    }

    // PR 자동 4단계 리뷰 결과 알림 (post-to-slack=true일 때 보조 채널로 사용)
    public void sendPrReview(PrReviewEvent event, PrReviewResult result) {
        if (!slackProperties.isEnabled() || !StringUtils.hasText(slackProperties.getWebhookUrl())) {
            log.info("Slack 비활성화/미설정 — PR 리뷰 {} #{} 전송 생략", event.getRepoFullName(), event.getPrNumber());
            return;
        }
        int findingCount = result.getMergedFindings() != null ? result.getMergedFindings().size() : 0;
        String header = "🤖 PR 자동 리뷰 · " + event.getRepoFullName() + " #" + event.getPrNumber();

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(mapOf(
                "type", "header",
                "text", mapOf("type", "plain_text", "text", abbreviate(header, HEADER_LIMIT), "emoji", true)));
        blocks.add(section("*종합 요약*\n" + nv(result.getOverallSummary())));
        blocks.add(section("*확정 이슈* " + findingCount + "건"));
        blocks.add(section("*리뷰어 집중 포인트*\n" + nv(result.getReviewerFocusNotes())));
        if (StringUtils.hasText(event.getPrHtmlUrl())) {
            blocks.add(actionBlock(event.getPrHtmlUrl(), "🔗 PR 열기"));
        }
        post(mapOf(
                "text", "PR #" + event.getPrNumber() + " 자동 리뷰 완료 (이슈 " + findingCount + "건)",
                "blocks", blocks));
    }

    public void sendPrReviewFailure(PrReviewEvent event, Throwable error) {
        if (!slackProperties.isEnabled() || !StringUtils.hasText(slackProperties.getWebhookUrl())) {
            return;
        }
        String errorSummary = error.getClass().getSimpleName() + ": " + abbreviate(error.getMessage(), 300);
        String text = "PR 자동 리뷰 실패: " + event.getRepoFullName() + " #" + event.getPrNumber() + " — " + errorSummary;

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(section(":warning: *PR 자동 리뷰 실패*\n"
                + event.getRepoFullName() + " #" + event.getPrNumber() + "\n`" + errorSummary + "`"));
        if (StringUtils.hasText(event.getPrHtmlUrl())) {
            blocks.add(actionBlock(event.getPrHtmlUrl(), "🔗 PR 열기"));
        }
        try {
            post(mapOf("text", text, "blocks", blocks));
        } catch (RuntimeException e) {
            log.warn("Slack PR 리뷰 실패 알림 전송도 실패", e);
        }
    }

    Map<String, Object> buildPayload(CommentEvent e, AnalysisResult r) {
        String location = e.isReviewComment() && StringUtils.hasText(e.getFilePath())
                ? " · " + e.getFilePath() + (e.getLine() != null ? ":" + e.getLine() : "")
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
        blocks.add(mapOf(
                "type", "context",
                "elements", Collections.singletonList(mapOf(
                        "type", "mrkdwn",
                        "text", "작성자 `" + nv(e.getCommentAuthor()) + "` · "
                                + (e.isReviewComment() ? "인라인 리뷰 코멘트" : "PR 일반 코멘트")))));

        return mapOf(
                "text", "PR #" + e.getPrNumber() + " 코멘트 분석: " + abbreviate(nv(r.getVerdict()), HEADER_LIMIT),
                "blocks", blocks);
    }

    private void post(Map<String, Object> payload) {
        try {
            String response = slackRestTemplate.postForObject(slackProperties.getWebhookUrl(), payload, String.class);
            if (response != null && !"ok".equalsIgnoreCase(response.trim())) {
                log.warn("Slack 응답이 ok가 아님: {}", response);
            }
        } catch (RestClientException e) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.SLACK_API_ERROR, e);
        }
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
