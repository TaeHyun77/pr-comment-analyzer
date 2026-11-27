package com.pr.automation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.CommentService;
import com.pr.automation.analysis.comment.CommentRepository;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.config.properties.GithubProperties;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
import com.pr.automation.slack.SlackNotifier;
import com.pr.automation.webhook.dto.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

// 웹훅 페이로드를 파싱 및 필터링하여 분석 대상이 되는 이벤트만 골라 분석 서비스로 전달하는 클래스
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventHandler {
    private static final String EVENT_REVIEW_COMMENT = "pull_request_review_comment";
    private static final String EVENT_ISSUE_COMMENT = "issue_comment";
    private static final String EVENT_PULL_REQUEST_REVIEW = "pull_request_review";
    private static final String ACTION_SUBMITTED = "submitted";
    private static final String ACTION_DELETED = "deleted";

    private final ObjectMapper objectMapper;
    private final GithubProperties githubProperties;
    private final CommentAnalyzerProperties commentAnalyzerProperties;
    private final CommentService commentService;
    private final CommentRepository commentRepository;
    private final SlackNotifier slackNotifier;

    public void handle(String event, String deliveryId, byte[] rawBody) {
        log.info("웹훅 수신: event={} delivery={}", event, deliveryId);

        // GitHub 웹훅을 새로 생성하거나 설정을 변경하면, GitHub가 가장 먼저 딱 한 번 ping 이벤트를 보내기에 분기
        if ("ping".equals(event)) {
            return;
        }

        if (!commentAnalyzerProperties.isEnabled()) return;

        // 코멘트 삭제는 분석 경로가 아니라 알림 경로로 빠지게 (분석한 적 있는 코멘트일 때만)
        if (isCommentDeletion(event, rawBody)) {
            handleCommentDeleted(event, deliveryId, rawBody);
            return;
        }

        // payload로부터 정보 추출
        Optional<CommentEvent> extracted = extract(event, rawBody);
        if (!extracted.isPresent()) {
            log.debug("웹훅 이벤트 무시 (event={}, delivery={})", event, deliveryId);
            return;
        }

        CommentEvent ce = extracted.get();
        if (ce.isReviewBody()) {
            commentService.analyzeReviewAsync(ce);
        } else {
            commentService.analyzeAsync(ce);
        }
    }

    private boolean isCommentDeletion(String event, byte[] rawBody) {
        if (!EVENT_ISSUE_COMMENT.equals(event) && !EVENT_REVIEW_COMMENT.equals(event)) return false;

        return parsePayload(event, rawBody)
                .map(p -> ACTION_DELETED.equals(p.getAction()))
                .orElse(false);
    }

    private void handleCommentDeleted(String event, String deliveryId, byte[] rawBody) {
        Optional<WebhookPayload> parsed = parsePayload(event, rawBody)
                .filter(p -> p.getComment() != null && p.getRepository() != null)
                .filter(this::isTargetRepo);
        if (!parsed.isPresent()) {
            return;
        }

        WebhookPayload payload = parsed.get();
        long commentId = payload.getComment().getId();
        if (!commentRepository.existsById(commentId)) {
            log.debug("분석한 적 없는 코멘트의 삭제 — 알림 생략: comment={} (delivery={})", commentId, deliveryId);
            return;
        }

        String repoFullName = payload.getRepository().getFullName();
        int prNumber = deletedPrNumber(payload);
        log.info("분석했던 코멘트 삭제 감지: {} #{} comment={} (delivery={})", repoFullName, prNumber, commentId, deliveryId);
        slackNotifier.sendCommentDeleted(repoFullName, prNumber, commentId, payload.getComment().getHtmlUrl());
    }

    // 리뷰 코멘트는 pull_request, 일반 코멘트는 issue에 PR 번호가 담긴다
    private static int deletedPrNumber(WebhookPayload payload) {
        if (payload.getPullRequest() != null) {
            return payload.getPullRequest().getNumber();
        }
        return payload.getIssue() != null ? payload.getIssue().getNumber() : 0;
    }

    public Optional<CommentEvent> extract(String event, byte[] rawBody) {
        // 리뷰 코멘트는 리뷰 제출 이벤트 하나로 묶어 분석한다 - 같은 리뷰의 코멘트를 claude 1회로 분석해 코드 탐색 중복을 없애기 위함
        // 단일 코멘트와 답글에도 GitHub이 리뷰 제출 이벤트를 함께 보내므로, 개별 코멘트 이벤트를 건너뛰어도 누락되지 않는다
        if (EVENT_PULL_REQUEST_REVIEW.equals(event)) {
            return extractReviewSubmission(rawBody);
        }
        if (!EVENT_ISSUE_COMMENT.equals(event)) {
            return Optional.empty();
        }

        // 파이프라인을 통한 데이터 파싱, 필터링 및 매핑
        return parsePayload(event, rawBody)
                .filter(this::isValidCreationEvent) // 기본 형태 검증 (created 여부 등)
                .filter(this::isTargetRepo) // github.repos에 등록된 레포인지 검증
                .filter(this::hasPrContext) // PR과 연관된 코멘트인지 검증
                .filter(this::passesBusinessRules) // 봇, 내 PR 여부 등 비즈니스 룰 검증
                .map(this::buildCommentEvent);  // 최종 CommentEvent DTO로 변환
    }

    // 총평이 비어 있어도 추출한다 - 인라인 코멘트만 담긴 리뷰도 분석 대상이고, 분석할 것이 있는지는 서비스가 코멘트를 조회해 판단한다
    private Optional<CommentEvent> extractReviewSubmission(byte[] rawBody) {
        return parsePayload(EVENT_PULL_REQUEST_REVIEW, rawBody)
                .filter(p -> ACTION_SUBMITTED.equals(p.getAction()))
                .filter(p -> p.getReview() != null && p.getPullRequest() != null && p.getRepository() != null)
                .filter(this::isTargetRepo)
                .filter(this::passesReviewRules)
                .map(this::buildReviewEvent);
    }

    private boolean isTargetRepo(WebhookPayload payload) {
        String fullName = payload.getRepository() != null ? payload.getRepository().getFullName() : null;
        if (!StringUtils.hasText(fullName)) {
            return false;
        }
        List<String> repos = githubProperties.getRepos();
        if (repos == null || repos.isEmpty()) {
            log.warn("github.repos 미설정 — 모든 웹훅을 차단함 (수신 repo={})", fullName);
            return false;
        }
        boolean matched = repos.stream().anyMatch(fullName::equalsIgnoreCase);
        if (!matched) {
            log.info("github.repos에 없는 저장소의 웹훅, 무시: {}", fullName);
        }
        return matched;
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

    // Issue 이벤트라도 순수 이슈 코멘트가 아닌 PR 코멘트여야 함
    private boolean hasPrContext(WebhookPayload payload) {
        return payload.getIssue() != null && payload.getIssue().getPullRequest() != null;
    }

    // 2. 비즈니스 룰 필터링
    private boolean passesBusinessRules(WebhookPayload payload) {
        WebhookPayload.Comment comment = payload.getComment();
        WebhookPayload.Issue issue = payload.getIssue();
        return passesAuthorRules(
                issue.getUser() != null ? issue.getUser().getLogin() : null,
                comment.getUser() != null ? comment.getUser().getLogin() : null,
                comment.getUser() != null ? comment.getUser().getType() : null);
    }

    // 리뷰의 코멘트는 모두 리뷰어 한 사람이 작성하므로 리뷰 단위로 한 번만 판정한다
    private boolean passesReviewRules(WebhookPayload payload) {
        WebhookPayload.Review review = payload.getReview();
        WebhookPayload.PullRequest pr = payload.getPullRequest();
        return passesAuthorRules(
                pr.getUser() != null ? pr.getUser().getLogin() : null,
                review.getUser() != null ? review.getUser().getLogin() : null,
                review.getUser() != null ? review.getUser().getType() : null);
    }

    // 내 PR에 달렸고, 작성자가 봇이 아니며, 내 글은 설정에 따라 포함할지 결정
    private boolean passesAuthorRules(String prAuthor, String author, String authorType) {
        if (!isMyPr(prAuthor)) return false;
        if (isBot(author, authorType)) return false;
        if (!commentAnalyzerProperties.isIncludeOwnComments() && isMe(author)) return false;

        return true;
    }

    // 3. 앞서 추출한 정보로 CommentEvent 객체 생성 (PR 일반 코멘트)
    private CommentEvent buildCommentEvent(WebhookPayload payload) {
        WebhookPayload.Comment comment = payload.getComment();
        WebhookPayload.Issue issue = payload.getIssue();

        return CommentEvent.builder()
                .eventType(CommentEvent.TYPE_ISSUE_COMMENT)
                .repoFullName(payload.getRepository().getFullName())
                .prNumber(issue.getNumber())
                .prTitle(issue.getTitle())
                .prBody(issue.getBody())
                .prAuthor(issue.getUser() != null ? issue.getUser().getLogin() : null)
                .headSha(null)
                .commentId(comment.getId())
                .commentBody(comment.getBody())
                .commentAuthor(comment.getUser() != null ? comment.getUser().getLogin() : null)
                .commentHtmlUrl(comment.getHtmlUrl())
                .build();
    }

    private CommentEvent buildReviewEvent(WebhookPayload payload) {
        WebhookPayload.Review review = payload.getReview();
        WebhookPayload.PullRequest pr = payload.getPullRequest();

        return CommentEvent.builder()
                .eventType(CommentEvent.TYPE_REVIEW_BODY)
                .repoFullName(payload.getRepository().getFullName())
                .prNumber(pr.getNumber())
                .prTitle(pr.getTitle())
                .prBody(pr.getBody())
                .prAuthor(pr.getUser() != null ? pr.getUser().getLogin() : null)
                .headSha(pr.getHead() != null ? pr.getHead().getSha() : null)
                .commentId(review.getId())
                .commentBody(review.getBody())
                .commentAuthor(review.getUser() != null ? review.getUser().getLogin() : null)
                .commentHtmlUrl(review.getHtmlUrl())
                .reviewState(review.getState())
                .build();
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
