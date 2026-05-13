package com.pr.automation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.CommentAnalysisService;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.config.GithubProperties;
import com.pr.automation.config.PrAnalyzerProperties;
import com.pr.automation.webhook.dto.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 웹훅 페이로드를 파싱/필터링해 분석 대상 코멘트만 골라냄
 * 통과한 코멘트는 {@link CommentAnalysisService#analyzeAsync(CommentEvent)}로 비동기 분석을 트리거한다.
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

    public void handle(String event, String deliveryId, byte[] rawBody) {
        if ("ping".equals(event)) {
            log.info("GitHub 웹훅 ping 수신 (delivery={})", deliveryId);
            return;
        }
        Optional<CommentEvent> extracted = extract(event, rawBody);
        if (!extracted.isPresent()) {
            log.debug("웹훅 이벤트 무시 (event={}, delivery={})", event, deliveryId);
            return;
        }
        CommentEvent ce = extracted.get();
        log.info("코멘트 분석 트리거: {} #{} comment={} (delivery={})",
                ce.getRepoFullName(), ce.getPrNumber(), ce.getCommentId(), deliveryId);
        commentAnalysisService.analyzeAsync(ce);
    }

    /**
     * 웹훅 페이로드에서 분석 대상 코멘트를 추출한다. 대상이 아니면(이벤트 타입/액션/PR 작성자/봇 등) 빈 값.
     */
    public Optional<CommentEvent> extract(String event, byte[] rawBody) {
        if (!EVENT_REVIEW_COMMENT.equals(event) && !EVENT_ISSUE_COMMENT.equals(event)) {
            return Optional.empty();
        }
        WebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (Exception e) {
            log.warn("웹훅 페이로드 파싱 실패 (event={})", event, e);
            return Optional.empty();
        }
        if (payload == null || !"created".equals(payload.getAction())
                || payload.getComment() == null || payload.getRepository() == null) {
            return Optional.empty();
        }

        WebhookPayload.Comment comment = payload.getComment();
        String commentAuthor = comment.getUser() != null ? comment.getUser().getLogin() : null;
        String commentAuthorType = comment.getUser() != null ? comment.getUser().getType() : null;

        String prAuthor;
        int prNumber;
        String prTitle;
        String prBody;
        String headSha;
        String eventType;
        if (EVENT_REVIEW_COMMENT.equals(event)) {
            WebhookPayload.PullRequest pr = payload.getPullRequest();
            if (pr == null) {
                return Optional.empty();
            }
            prAuthor = pr.getUser() != null ? pr.getUser().getLogin() : null;
            prNumber = pr.getNumber();
            prTitle = pr.getTitle();
            prBody = pr.getBody();
            headSha = pr.getHead() != null ? pr.getHead().getSha() : null;
            eventType = CommentEvent.TYPE_REVIEW_COMMENT;
        } else {
            WebhookPayload.Issue issue = payload.getIssue();
            if (issue == null || issue.getPullRequest() == null) {
                // PR이 아닌 순수 이슈 코멘트
                return Optional.empty();
            }
            prAuthor = issue.getUser() != null ? issue.getUser().getLogin() : null;
            prNumber = issue.getNumber();
            prTitle = issue.getTitle();
            prBody = issue.getBody();
            headSha = null;
            eventType = CommentEvent.TYPE_ISSUE_COMMENT;
        }

        if (!isMyPr(prAuthor)) {
            return Optional.empty();
        }
        if (isBot(commentAuthor, commentAuthorType)) {
            return Optional.empty();
        }
        if (!prAnalyzerProperties.isIncludeOwnComments() && isMe(commentAuthor)) {
            return Optional.empty();
        }

        Integer line = comment.getLine() != null ? comment.getLine() : comment.getOriginalLine();
        return Optional.of(CommentEvent.builder()
                .eventType(eventType)
                .repoFullName(payload.getRepository().getFullName())
                .prNumber(prNumber)
                .prTitle(prTitle)
                .prBody(prBody)
                .headSha(headSha)
                .commentId(comment.getId())
                .commentBody(comment.getBody())
                .commentAuthor(commentAuthor)
                .commentHtmlUrl(comment.getHtmlUrl())
                .filePath(comment.getPath())
                .diffHunk(comment.getDiffHunk())
                .line(line)
                .inReplyToId(comment.getInReplyToId())
                .build());
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
