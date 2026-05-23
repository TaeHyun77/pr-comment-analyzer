package com.pr.automation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.CommentAnalysisService;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.config.GithubProperties;
import com.pr.automation.config.PrAnalyzerProperties;
import com.pr.automation.webhook.dto.WebhookPayload;
import com.pr.automation.webhook.recovery.DeliveryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 웹훅 페이로드를 파싱/필터링해 분석 대상 코멘트만 골라냄
 * 통과한 코멘트는 비동기 분석을 트리거함
 *
 * deliveryStore.markReceived는 ack 완료 시점에만 호출 — ping/필터 탈락은 즉시, 분석 대상은 CommentAnalysisService가 분석 성공 후 호출함
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventHandler {
    private static final String EVENT_REVIEW_COMMENT = "pull_request_review_comment";
    private static final String EVENT_ISSUE_COMMENT = "issue_comment";

    private final ObjectMapper objectMapper;
    private final GithubProperties githubProperties;
    private final PrAnalyzerProperties prAnalyzerProperties;
    private final CommentAnalysisService commentAnalysisService;
    private final DeliveryStore deliveryStore;

    public void handle(String event, String deliveryId, byte[] rawBody) {
        // GitHub 웹훅을 새로 생성하거나 설정을 변경하면, GitHub가 가장 먼저 딱 한 번 ping 이벤트를 보내기에 분기함
        if ("ping".equals(event)) {
            log.info("GitHub 웹훅 ping 수신 (delivery={})", deliveryId);
            deliveryStore.markReceived(deliveryId);
            return;
        }

        // payload로부터 정보 추출
        Optional<CommentEvent> extracted = extract(event, deliveryId, rawBody);
        if (!extracted.isPresent()) {
            log.debug("웹훅 이벤트 무시 (event={}, delivery={})", event, deliveryId);
            // 분석 대상이 아닌 webhook도 ack — 복구 대상에서 제외
            deliveryStore.markReceived(deliveryId);
            return;
        }

        CommentEvent ce = extracted.get();
        if (log.isDebugEnabled()) {
            try {
                log.debug("수신 CommentEvent={}", objectMapper.writeValueAsString(ce));
            } catch (Exception ignore) {
                // 디버그용 직렬화 실패는 무시
            }
        }
        log.info("코멘트 분석 트리거: {} #{} comment={} (delivery={})", ce.getRepoFullName(), ce.getPrNumber(), ce.getCommentId(), deliveryId);
        // 분석 분기는 여기서 markReceived 호출하지 않음 — 분석 성공 시점에 CommentAnalysisService가 호출
        commentAnalysisService.analyzeAsync(ce);
    }

    public Optional<CommentEvent> extract(String event, String deliveryId, byte[] rawBody) {
        if (!isSupportedEvent(event)) { // 지원하는 이벤트인지 확인
            return Optional.empty();
        }

        // 파이프라인을 통한 데이터 파싱, 필터링 및 매핑
        return parsePayload(event, rawBody)
                .filter(this::isValidCreationEvent) // 기본 형태 검증 (created 여부 등)
                .filter(p -> hasPrContext(event, p)) // PR과 연관된 코멘트인지 검증
                .filter(p -> passesBusinessRules(event, p)) // 봇, 내 PR 여부 등 비즈니스 룰 검증
                .map(p -> buildCommentEvent(event, deliveryId, p));  // 최종 CommentEvent DTO로 변환
    }

    // 1. 파싱 및 기본 검증
    private boolean isSupportedEvent(String event) {
        return EVENT_REVIEW_COMMENT.equals(event) || EVENT_ISSUE_COMMENT.equals(event);
    }

    private Optional<WebhookPayload> parsePayload(String event, byte[] rawBody) {
        try {
            return Optional.ofNullable(objectMapper.readValue(rawBody, WebhookPayload.class));
        } catch (Exception e) {
            log.warn("웹훅 페이로드 파싱 실패 (event={})", event, e);
            return Optional.empty();
        }
    }

    private boolean isValidCreationEvent(WebhookPayload payload) {
        return payload != null
                && "created".equals(payload.getAction())
                && payload.getComment() != null
                && payload.getRepository() != null;
    }

    private boolean hasPrContext(String event, WebhookPayload payload) {
        if (EVENT_REVIEW_COMMENT.equals(event)) {
            return payload.getPullRequest() != null;
        }
        if (EVENT_ISSUE_COMMENT.equals(event)) {
            // Issue 이벤트라도 순수 이슈 코멘트가 아닌 PR 코멘트여야 함
            return payload.getIssue() != null && payload.getIssue().getPullRequest() != null;
        }
        return false;
    }

    // 2. 비즈니스 룰 필터링
    private boolean passesBusinessRules(String event, WebhookPayload payload) {
        WebhookPayload.Comment comment = payload.getComment();
        String commentAuthor = comment.getUser() != null ? comment.getUser().getLogin() : null;
        String commentAuthorType = comment.getUser() != null ? comment.getUser().getType() : null;
        String prAuthor = extractPrAuthor(event, payload);

        if (!isMyPr(prAuthor)) return false;
        if (isBot(commentAuthor, commentAuthorType)) return false;
        if (!prAnalyzerProperties.isIncludeOwnComments() && isMe(commentAuthor)) return false;

        return true;
    }

    private String extractPrAuthor(String event, WebhookPayload payload) {
        if (EVENT_REVIEW_COMMENT.equals(event)) {
            return payload.getPullRequest().getUser() != null ? payload.getPullRequest().getUser().getLogin() : null;
        } else {
            return payload.getIssue().getUser() != null ? payload.getIssue().getUser().getLogin() : null;
        }
    }

    // 3. 앞서 추출한 정보로 CommentEvent 객체 생성
    private CommentEvent buildCommentEvent(String event, String deliveryId, WebhookPayload payload) {
        WebhookPayload.Comment comment = payload.getComment();
        Integer line = comment.getLine() != null ? comment.getLine() : comment.getOriginalLine();

        // 공통 필드 매핑
        CommentEvent.CommentEventBuilder builder = CommentEvent.builder()
                .deliveryId(deliveryId)
                .repoFullName(payload.getRepository().getFullName())
                .commentId(comment.getId())
                .commentBody(comment.getBody())
                .commentAuthor(comment.getUser() != null ? comment.getUser().getLogin() : null)
                .commentHtmlUrl(comment.getHtmlUrl())
                .filePath(comment.getPath())
                .diffHunk(comment.getDiffHunk())
                .line(line)
                .inReplyToId(comment.getInReplyToId());

        // 이벤트 타입별 분기 매핑
        if (EVENT_REVIEW_COMMENT.equals(event)) {
            WebhookPayload.PullRequest pr = payload.getPullRequest();
            builder.eventType(CommentEvent.TYPE_REVIEW_COMMENT)
                    .prNumber(pr.getNumber())
                    .prTitle(pr.getTitle())
                    .prBody(pr.getBody())
                    .headSha(pr.getHead() != null ? pr.getHead().getSha() : null);
        } else {
            WebhookPayload.Issue issue = payload.getIssue();
            builder.eventType(CommentEvent.TYPE_ISSUE_COMMENT)
                    .prNumber(issue.getNumber())
                    .prTitle(issue.getTitle())
                    .prBody(issue.getBody())
                    .headSha(null);
        }

        return builder.build();
    }

    private boolean isMyPr(String prAuthor) {
        return prAuthor != null && prAuthor.equalsIgnoreCase(githubProperties.getLogin());
    }

    private boolean isMe(String login) {
        return login != null && login.equalsIgnoreCase(githubProperties.getLogin());
    }

    private static boolean isBot(String login, String type) {
        return "Bot".equalsIgnoreCase(type) || (login != null && login.endsWith("[bot]"));
    }
}
