package com.pr.automation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.CommentAnalysisService;
import com.pr.automation.analysis.comment.CommentStore;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.config.properties.GithubProperties;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
import com.pr.automation.config.properties.PrReviewProperties;
import com.pr.automation.github.GithubClient;
import com.pr.automation.analysis.pr.PrReviewService;
import com.pr.automation.slack.SlackNotifier;
import com.pr.automation.analysis.pr.dto.PrReviewEvent;
import com.pr.automation.webhook.dto.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 웹훅 페이로드를 파싱/필터링해서, 분석 대상이 되는 이벤트만 골라 각 서비스로 라우팅하는 클래스
 * ping, PR 생성, 코멘트 생성 등 이벤트 종류별 분기와, 봇/타인 PR 등 비즈니스 룰 필터링을 모두 여기서 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventHandler {
    private static final String EVENT_REVIEW_COMMENT = "pull_request_review_comment";
    private static final String EVENT_ISSUE_COMMENT = "issue_comment";
    private static final String EVENT_PULL_REQUEST = "pull_request";
    private static final String EVENT_PULL_REQUEST_REVIEW = "pull_request_review";
    private static final String EVENT_COMMIT_COMMENT = "commit_comment";
    private static final String ACTION_OPENED = "opened";
    private static final String ACTION_READY_FOR_REVIEW = "ready_for_review";
    private static final String ACTION_SUBMITTED = "submitted";
    private static final String ACTION_DELETED = "deleted";

    private final ObjectMapper objectMapper;
    private final GithubProperties githubProperties;
    private final CommentAnalyzerProperties commentAnalyzerProperties;
    private final CommentAnalysisService commentAnalysisService;
    private final CommentStore commentStore;
    private final SlackNotifier slackNotifier;
    private final PrReviewService prReviewService;
    private final PrReviewProperties prReviewProperties;
    private final GithubClient githubClient;

    public void handle(String event, String deliveryId, byte[] rawBody) {
        // GitHub 웹훅을 새로 생성하거나 설정을 변경하면, GitHub가 가장 먼저 딱 한 번 ping 이벤트를 보내기에 분기
        if ("ping".equals(event)) {
            return;
        }

        // PR 생성 이벤트 → 에이전트 자동 리뷰 경로로 위임
        if (EVENT_PULL_REQUEST.equals(event)) {
            handlePullRequest(deliveryId, rawBody); // PR 이벤트 분석 여부는 여기서 체크
            return;
        }

        // 코멘트 분석 전역 kill switch
        if (!commentAnalyzerProperties.isEnabled()) {
            return;
        }

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
        commentAnalysisService.analyzeAsync(ce);
    }

    // PR 생성 처리 : 내 PR이고 봇이 아니면 에이전트 리뷰 진행
    private void handlePullRequest(String deliveryId, byte[] rawBody) {
        Optional<PrReviewEvent> extracted = extractPullRequest(rawBody);
        if (!extracted.isPresent()) return;

        PrReviewEvent pre = extracted.get();
        prReviewService.reviewAsync(pre);
    }

    // pull_request 페이로드를 파싱/필터링해 PrReviewEvent로 변환 ( pr-review 비활성이거나 대상 아니면 empty )
    public Optional<PrReviewEvent> extractPullRequest(byte[] rawBody) {
        if (!prReviewProperties.isEnabled()) return Optional.empty();

        return parsePayload(EVENT_PULL_REQUEST, rawBody)
                .filter(p -> p.getPullRequest() != null && p.getRepository() != null)
                .filter(this::isTargetRepo)
                .filter(WebhookEventHandler::isReviewableAction)
                .filter(this::passesPrReviewRules)
                .map(this::buildPrReviewEvent);
    }

    // Draft로 열린 PR은 완성 전이므로 건너뛰고, Ready로 전환되는 시점에 리뷰
    // 두 액션이 모두 발생해도 PrReviewService가 게시된 마커 코멘트를 보고 리뷰를 생략한다
    private static boolean isReviewableAction(WebhookPayload payload) {
        String action = payload.getAction();
        if (ACTION_READY_FOR_REVIEW.equals(action)) return true;

        return ACTION_OPENED.equals(action) && !Boolean.TRUE.equals(payload.getPullRequest().getDraft());
    }

    // PR 작성자가 나이고, 봇이 아니어야 함
    private boolean passesPrReviewRules(WebhookPayload payload) {
        WebhookPayload.PullRequest pr = payload.getPullRequest();
        String prAuthor = pr.getUser() != null ? pr.getUser().getLogin() : null;
        String prAuthorType = pr.getUser() != null ? pr.getUser().getType() : null;

        return isMyPr(prAuthor) && !isBot(prAuthor, prAuthorType);
    }

    // 파싱된 페이로드에서 저장소/PR 번호/제목/본문/head SHA/작성자/URL을 뽑아 PrReviewEvent로 변환
    private PrReviewEvent buildPrReviewEvent(WebhookPayload payload) {
        WebhookPayload.PullRequest pr = payload.getPullRequest();
        return PrReviewEvent.builder()
                .repoFullName(payload.getRepository().getFullName())
                .prNumber(pr.getNumber())
                .prTitle(pr.getTitle())
                .prBody(pr.getBody())
                .headSha(pr.getHead() != null ? pr.getHead().getSha() : null)
                .prAuthor(pr.getUser() != null ? pr.getUser().getLogin() : null)
                .prHtmlUrl(pr.getHtmlUrl())
                .build();
    }

    /** 코멘트 삭제 이벤트인지 판별
     * deleted 액션을 보내는 건 issue_comment와 pull_request_review_comment 둘 뿐
     * commit_comment는 created만 오고, pull_request_review는 dismissed로 옴
     */
    private boolean isCommentDeletion(String event, byte[] rawBody) {
        if (!EVENT_ISSUE_COMMENT.equals(event) && !EVENT_REVIEW_COMMENT.equals(event)) return false;

        return parsePayload(event, rawBody)
                .map(p -> ACTION_DELETED.equals(p.getAction()))
                .orElse(false);
    }

    /** 삭제된 코멘트가 분석 대상이었던 경우에만 Slack으로 알림
     * 분석한 적 없는 코멘트(봇/타인 PR 등으로 걸러졌거나 애초에 대상이 아니었던 것)의 삭제까지 알리면
     * 오타 코멘트를 지우는 일상적인 행위가 전부 알림이 되므로, 상태 행이 있는 코멘트로 한정
     */
    private void handleCommentDeleted(String event, String deliveryId, byte[] rawBody) {
        Optional<WebhookPayload> parsed = parsePayload(event, rawBody)
                .filter(p -> p.getComment() != null && p.getRepository() != null)
                .filter(this::isTargetRepo);
        if (!parsed.isPresent()) {
            return;
        }

        WebhookPayload payload = parsed.get();
        long commentId = payload.getComment().getId();
        if (!commentStore.isTracked(commentId)) {
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
        // 리뷰 총평은 payload 구조가 comment가 아닌 review 기준이라 별도 경로로 처리
        if (EVENT_PULL_REQUEST_REVIEW.equals(event)) {
            return extractReviewBody(rawBody);
        }
        // 커밋 코멘트는 payload에 PR 정보가 없어 API로 소속 PR을 채워야 하므로 별도 경로로 처리
        if (EVENT_COMMIT_COMMENT.equals(event)) {
            return extractCommitComment(rawBody);
        }
        if (!isSupportedEvent(event)) { // 지원하는 이벤트인지 확인
            return Optional.empty();
        }

        // 파이프라인을 통한 데이터 파싱, 필터링 및 매핑
        return parsePayload(event, rawBody)
                .filter(this::isValidCreationEvent) // 기본 형태 검증 (created 여부 등)
                .filter(this::isTargetRepo) // github.repos에 등록된 레포인지 검증
                .filter(p -> hasPrContext(event, p)) // PR과 연관된 코멘트인지 검증
                .filter(p -> passesBusinessRules(event, p)) // 봇, 내 PR 여부 등 비즈니스 룰 검증
                .map(p -> buildCommentEvent(event, p));  // 최종 CommentEvent DTO로 변환
    }

    /** 리뷰 제출(Submit review) 시 남긴 총평 본문만 추출
     * 같은 리뷰에 달린 인라인 코멘트는 pull_request_review_comment로 따로 오므로 여기서 다루지 않음
     * Approve만 누르면 body가 null이 아닌 빈 문자열로 오고, 단일 코멘트 게시도 body가 빈 리뷰를 만들므로 hasText로 걸러야 무의미한 분석과 중복 트리거가 동시에 차단됨
     */
    private Optional<CommentEvent> extractReviewBody(byte[] rawBody) {
        return parsePayload(EVENT_PULL_REQUEST_REVIEW, rawBody)
                .filter(p -> ACTION_SUBMITTED.equals(p.getAction()))
                .filter(p -> p.getReview() != null && p.getPullRequest() != null && p.getRepository() != null)
                .filter(this::isTargetRepo)
                .filter(p -> StringUtils.hasText(p.getReview().getBody()))
                .filter(this::passesReviewBodyRules)
                .map(this::buildReviewBodyEvent);
    }

    /** 커밋 자체에 달린 코멘트를, 그 커밋이 속한 열린 PR의 코멘트로 간주해 분석 대상으로 만듦
     * payload에 pull_request도 issue도 없어 PR 번호와 작성자를 알 수 없으므로, commit_id로 소속 PR을 1회 조회
     *
     * 웹훅 수신 경로에서 유일하게 외부 API를 호출하는 지점
     * 열린 PR이 없으면 분석 대상이 아니라 empty를 돌려주고(호출부가 ack), 조회 자체가 실패하면 예외가 전파되어 ack 없이 복구 사이클의 재전송 대상으로 남음
     */
    private Optional<CommentEvent> extractCommitComment(byte[] rawBody) {
        Optional<WebhookPayload> parsed = parsePayload(EVENT_COMMIT_COMMENT, rawBody)
                .filter(this::isValidCreationEvent)
                .filter(this::isTargetRepo)
                .filter(p -> StringUtils.hasText(p.getComment().getCommitId()));
        if (!parsed.isPresent()) {
            return Optional.empty();
        }

        WebhookPayload payload = parsed.get();
        WebhookPayload.Comment comment = payload.getComment();

        Optional<GithubClient.PullRef> pullOpt = githubClient.fetchOpenPullForCommit(
                payload.getRepository().getFullName(), comment.getCommitId());
        if (!pullOpt.isPresent()) {
            log.info("커밋이 속한 열린 PR 없음, 분석 건너뜀: {} sha={}",
                    payload.getRepository().getFullName(), comment.getCommitId());
            return Optional.empty();
        }
        GithubClient.PullRef pull = pullOpt.get();

        boolean allowed = passesAuthorRules(
                pull.getAuthor(),
                comment.getUser() != null ? comment.getUser().getLogin() : null,
                comment.getUser() != null ? comment.getUser().getType() : null);
        if (!allowed) {
            return Optional.empty();
        }

        return Optional.of(CommentEvent.builder()
                .eventType(CommentEvent.TYPE_COMMIT_COMMENT)
                .repoFullName(payload.getRepository().getFullName())
                .prNumber(pull.getNumber())
                .prTitle(pull.getTitle())
                .prBody(pull.getBody())
                // 코멘트가 달린 커밋을 파일 조회 기준으로 삼는다 — PR head를 쓰면 코멘트 시점과 어긋난다
                .headSha(comment.getCommitId())
                .commentId(comment.getId())
                .commentBody(comment.getBody())
                .commentAuthor(comment.getUser() != null ? comment.getUser().getLogin() : null)
                .commentHtmlUrl(comment.getHtmlUrl())
                .filePath(comment.getPath())
                .line(comment.getLine())
                .build());
    }

    // issue_comment 또는 pull_request_review_comment만 인정
    private boolean isSupportedEvent(String event) {
        return EVENT_REVIEW_COMMENT.equals(event) || EVENT_ISSUE_COMMENT.equals(event);
    }

    /**
     * github.repos에 등록된 저장소의 웹훅만 처리한다.
     * 여러 저장소에 같은 webhook secret을 쓰는 구성이 가능하므로, 서명 검증만으로는 대상 저장소를 한정할 수 없다.
     * 목록이 비면 전부 차단한다(fail-closed) — 복구 경로도 github.repos만 순회하므로 대상 집합을 일치시킨다.
     */
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
        return passesAuthorRules(
                extractPrAuthor(event, payload),
                comment.getUser() != null ? comment.getUser().getLogin() : null,
                comment.getUser() != null ? comment.getUser().getType() : null);
    }

    private boolean passesReviewBodyRules(WebhookPayload payload) {
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

    private String extractPrAuthor(String event, WebhookPayload payload) {
        if (EVENT_REVIEW_COMMENT.equals(event)) {
            return payload.getPullRequest().getUser() != null ? payload.getPullRequest().getUser().getLogin() : null;
        } else {
            return payload.getIssue().getUser() != null ? payload.getIssue().getUser().getLogin() : null;
        }
    }

    // 3. 앞서 추출한 정보로 CommentEvent 객체 생성
    private CommentEvent buildCommentEvent(String event, WebhookPayload payload) {
        WebhookPayload.Comment comment = payload.getComment();

        // 공통 필드 매핑 — line/originalLine은 기준 커밋이 다르므로 합치지 않고 그대로 보존
        CommentEvent.CommentEventBuilder builder = CommentEvent.builder()
                .repoFullName(payload.getRepository().getFullName())
                .commentId(comment.getId())
                .commentBody(comment.getBody())
                .commentAuthor(comment.getUser() != null ? comment.getUser().getLogin() : null)
                .commentHtmlUrl(comment.getHtmlUrl())
                .filePath(comment.getPath())
                .diffHunk(comment.getDiffHunk())
                .line(comment.getLine())
                .side(comment.getSide())
                .startLine(comment.getStartLine())
                .originalLine(comment.getOriginalLine())
                .inReplyToId(comment.getInReplyToId())
                .subjectType(comment.getSubjectType());

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

    // 리뷰 총평은 파일/라인 정보가 없으므로 위치 관련 필드는 채우지 않는다.
    // commentId에는 review.id를 넣는다 — 코멘트 ID와 다른 시퀀스지만 CommentStore의 중복 판정 키로 쓰기에 충분하다
    private CommentEvent buildReviewBodyEvent(WebhookPayload payload) {
        WebhookPayload.Review review = payload.getReview();
        WebhookPayload.PullRequest pr = payload.getPullRequest();

        return CommentEvent.builder()
                .eventType(CommentEvent.TYPE_REVIEW_BODY)
                .repoFullName(payload.getRepository().getFullName())
                .prNumber(pr.getNumber())
                .prTitle(pr.getTitle())
                .prBody(pr.getBody())
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
