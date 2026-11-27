package com.pr.automation.analysis.comment;

import com.pr.automation.analysis.comment.agent.CommentAnalyzer;
import com.pr.automation.analysis.comment.dto.CommentAnalysisResult;
import com.pr.automation.analysis.comment.dto.CommentContext;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.github.RepoCheckout;
import com.pr.automation.github.RepoCheckoutFactory;
import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import com.pr.automation.config.AsyncConfig;
import com.pr.automation.github.GithubClient;
import com.pr.automation.slack.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {
    static final int MAX_BATCH_SIZE = 10;

    private final GithubClient githubClient;
    private final CommentAnalyzer analysisAgent;
    private final SlackNotifier slackNotifier;
    private final CommentRepository commentRepository;
    private final RepoCheckoutFactory repoCheckoutFactory;

    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    public void analyzeAsync(CommentEvent event) {
        if (isAlreadyAnalyzed(event)) {
            return;
        }
        analyzeOne(event);
    }

    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    public void analyzeReviewAsync(CommentEvent review) {
        List<CommentEvent> targets;
        try {
            targets = collectReviewTargets(review);
        } catch (Exception e) {
            log.error("리뷰 코멘트 수집 실패: {} #{} review={}", review.getRepoFullName(), review.getPrNumber(), review.getCommentId(), e);
            slackNotifier.sendFailure(review, e);
            return;
        }

        // 재전송으로 같은 리뷰가 다시 오면 이미 분석한 코멘트만 빠지고, 실패했던 코멘트는 다시 분석
        targets.removeIf(this::isAlreadyAnalyzed);
        if (targets.isEmpty()) {
            log.debug("리뷰에 분석할 코멘트 없음: {} #{} review={}", review.getRepoFullName(), review.getPrNumber(), review.getCommentId());
            return;
        }

        // 1건이면 묶을 이점이 없으므로 검증된 단건 경로를 그대로 쓴다 (단일 코멘트, 답글, 총평만 있는 리뷰)
        if (targets.size() == 1) {
            analyzeOne(targets.get(0));
            return;
        }
        for (int from = 0; from < targets.size(); from += MAX_BATCH_SIZE) {
            analyzeBatch(targets.subList(from, Math.min(from + MAX_BATCH_SIZE, targets.size())));
        }
    }

    private void analyzeOne(CommentEvent event) {
        CommentAnalysisResult result;
        try {
            result = runAnalysis(event);
        } catch (Exception e) {
            notifyFailure(event, e);
            return;
        }
        saveAndNotify(event, result);
    }

    private void analyzeBatch(List<CommentEvent> events) {
        CommentEvent first = events.get(0);
        Map<Long, CommentAnalysisResult> results;
        try {
            String headSha = resolveHeadSha(first);
            try (RepoCheckout checkout = repoCheckoutFactory.checkout(first.getRepoFullName(), headSha)) {
                List<CommentContext> contexts = events.stream().map(this::buildContext).collect(Collectors.toList());
                results = analysisAgent.analyzeBatch(contexts, checkout.dir());
            }
        } catch (Exception e) {
            events.forEach(ev -> notifyFailure(ev, e));
            return;
        }

        for (CommentEvent event : events) {
            CommentAnalysisResult result = results.get(event.getCommentId());
            if (result == null) {
                // 저장하지 않으므로 재전송으로 다시 시도할 수 있다
                notifyFailure(event, new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR,
                        "묶음 분석 응답에 이 코멘트의 결과가 없음"));
                continue;
            }
            saveAndNotify(event, result);
        }
    }

    // 리뷰에 속한 인라인 코멘트(작성 순)와, 총평이 있으면 총평을 분석 대상으로 모은다
    private List<CommentEvent> collectReviewTargets(CommentEvent review) {
        if (!githubClient.isEnabled()) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REPO_NOT_READABLE, "GitHub 토큰 미설정으로 리뷰 코멘트 조회 불가");
        }
        // 조회 실패를 빈 목록으로 삼키면 인라인 코멘트가 조용히 누락되므로 실패로 처리한다
        List<GithubClient.FetchedComment> all = githubClient.fetchPullReviewComments(review.getRepoFullName(), review.getPrNumber())
                .orElseThrow(() -> new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.REPO_NOT_READABLE,
                        "PR 리뷰 코멘트 목록 조회 실패 (PR #" + review.getPrNumber() + ")"));

        List<CommentEvent> targets = all.stream()
                .filter(c -> c.getPullRequestReviewId() != null && c.getPullRequestReviewId() == review.getCommentId())
                .sorted(Comparator.comparingLong(GithubClient.FetchedComment::getId))
                .map(c -> toReviewCommentEvent(review, c))
                .collect(Collectors.toCollection(ArrayList::new));
        if (StringUtils.hasText(review.getCommentBody())) {
            targets.add(review);
        }
        return targets;
    }

    // line/originalLine은 기준 커밋이 다르므로 합치지 않고 그대로 보존
    private static CommentEvent toReviewCommentEvent(CommentEvent review, GithubClient.FetchedComment c) {
        return CommentEvent.builder()
                .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
                .repoFullName(review.getRepoFullName())
                .prNumber(review.getPrNumber())
                .prTitle(review.getPrTitle())
                .prBody(review.getPrBody())
                .prAuthor(review.getPrAuthor())
                .headSha(review.getHeadSha())
                .commentId(c.getId())
                .commentBody(c.getBody())
                .commentAuthor(c.getAuthor())
                .commentHtmlUrl(c.getHtmlUrl())
                .filePath(c.getFilePath())
                .line(c.getLine())
                .side(c.getSide())
                .startLine(c.getStartLine())
                .originalLine(c.getOriginalLine())
                .inReplyToId(c.getInReplyToId())
                .subjectType(c.getSubjectType())
                .build();
    }

    private void saveAndNotify(CommentEvent event, CommentAnalysisResult result) {
        // 생략 비율을 로그로 집계할 수 있어야 체크아웃 전 사전 분류를 도입할지 판단할 수 있다
        if (result.isSkipped()) {
            log.info("분석 생략 판정: {} #{} comment={} - {}", event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), result.getVerdict());
        }
        saveResult(event, result);

        try {
            slackNotifier.send(event, result);
        } catch (Exception e) {
            // 재시도 경로가 없어 알림은 유실되지만 결과는 DB에 남아 있다
            log.error("분석 결과 통지 실패: {} #{} comment={}", event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), e);
        }
    }

    private void notifyFailure(CommentEvent event, Exception e) {
        log.error("코멘트 분석 실패: {} #{} comment={}", event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), e);
        slackNotifier.sendFailure(event, e);
    }

    // DB 장애로 확인할 수 없으면 분석을 진행 - 결과 저장 실패를 허용하는 saveResult와 같은 기준으로, 중복 위험보다 분석 누락을 더 피한다
    private boolean isAlreadyAnalyzed(CommentEvent event) {
        try {
            boolean analyzed = commentRepository.existsById(event.getCommentId());
            if (analyzed) {
                log.info("이미 분석한 코멘트, 건너뜀: {} #{} comment={}", event.getRepoFullName(), event.getPrNumber(), event.getCommentId());
            }
            return analyzed;
        } catch (DataAccessException e) {
            log.warn("중복 분석 여부 확인 실패, 분석 진행: {} #{} comment={}", event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), e);
            return false;
        }
    }

    private void saveResult(CommentEvent event, CommentAnalysisResult result) {
        try {
            commentRepository.save(Comment.from(event, result, Instant.now()));
        } catch (DataAccessException e) {
            log.error("분석 결과 저장 실패: {} #{} comment={}", event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), e);
        }
    }

    private CommentAnalysisResult runAnalysis(CommentEvent event) {
        String headSha = resolveHeadSha(event);

        try (RepoCheckout checkout = repoCheckoutFactory.checkout(event.getRepoFullName(), headSha)) {
            return analysisAgent.analyze(buildContext(event), checkout.dir());
        }
    }

    // 코멘트가 가리키는 커밋. 이벤트에 없으면(issue_comment 등) PR의 head를 조회해 채움
    private String resolveHeadSha(CommentEvent e) {
        if (!githubClient.isEnabled()) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REPO_NOT_READABLE, "GitHub 토큰 미설정으로 레포 조회 불가");
        }

        String headSha = e.getHeadSha();
        if (!StringUtils.hasText(headSha)) {
            headSha = githubClient.fetchPullHeadSha(e.getRepoFullName(), e.getPrNumber()).orElse(null);
        }
        if (!StringUtils.hasText(headSha)) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REPO_NOT_READABLE, "PR head SHA를 확인할 수 없음 (PR #" + e.getPrNumber() + ")");
        }
        return headSha;
    }

    // CommentEvent를 CommentContext로 변환
    private CommentContext buildContext(CommentEvent e) {
        List<String> parents = buildParentThread(e);

        return CommentContext.builder()
                .commentId(e.getCommentId())
                .eventType(e.getEventType())
                .repoFullName(e.getRepoFullName())
                .prNumber(e.getPrNumber())
                .prTitle(e.getPrTitle())
                .prBody(e.getPrBody())
                .prAuthor(e.getPrAuthor())
                .headSha(e.getHeadSha())
                .filePath(e.getFilePath())
                .line(e.getLine())
                .side(e.getSide())
                .startLine(e.getStartLine())
                .originalLine(e.getOriginalLine())
                .fileLevel(e.isFileLevel())
                .reviewState(e.getReviewState())
                .filePatch(fetchFilePatch(e))
                .parentComments(parents)
                .commentAuthor(e.getCommentAuthor())
                .commentBody(e.getCommentBody())
                .build();
    }

    private List<String> buildParentThread(CommentEvent e) {
        if (e.getInReplyToId() == null || !githubClient.isEnabled()) {
            return new ArrayList<>();
        }
        long rootId = e.getInReplyToId();

        List<GithubClient.FetchedComment> all = githubClient.fetchPullReviewComments(e.getRepoFullName(), e.getPrNumber()).orElse(null);
        if (all == null) {
            List<String> fallback = new ArrayList<>();
            githubClient.fetchReviewComment(e.getRepoFullName(), rootId)
                    .ifPresent(root -> fallback.add(formatThreadComment(root.getAuthor(), root.getBody(), e.getPrAuthor())));
            return fallback;
        }

        List<GithubClient.FetchedComment> thread = all.stream()
                .filter(c -> c.getId() == rootId || (c.getInReplyToId() != null && c.getInReplyToId() == rootId))
                .filter(c -> c.getId() < e.getCommentId()) // 현재 코멘트와 그 이후는 제외
                .sorted(Comparator.comparingLong(GithubClient.FetchedComment::getId))
                .collect(Collectors.toCollection(ArrayList::new));

        List<String> parents = new ArrayList<>();
        for (GithubClient.FetchedComment c : thread) {
            parents.add(formatThreadComment(c.getAuthor(), c.getBody(), e.getPrAuthor()));
        }
        return parents;
    }

    // PR 작성자의 말에만 표시를 붙인다 - 같은 말도 작성자의 해명인지 다른 참여자의 의견인지에 따라 대화의 의미가 달라진다
    // 나머지 참여자는 리뷰어가 아닐 수도 있어 역할을 단정하지 않고 로그인만 남긴다
    static String formatThreadComment(String author, String body, String prAuthor) {
        boolean isPrAuthor = author != null && author.equalsIgnoreCase(prAuthor);
        return "@" + author + (isPrAuthor ? " (PR 작성자)" : "") + ": " + (body == null ? "" : body);
    }

    private String fetchFilePatch(CommentEvent e) {
        if (!StringUtils.hasText(e.getFilePath()) || !githubClient.isEnabled()) {
            return null;
        }

        // 조회 실패와 미발견 모두 null - patch는 보조 맥락이므로 실패를 삼키고 분석을 계속함
        String patch = githubClient.fetchPullFiles(e.getRepoFullName(), e.getPrNumber())
                .flatMap(files -> files.stream()
                        .filter(f -> e.getFilePath().equals(f.getFilename()))
                        .findFirst())
                .map(GithubClient.ChangedFile::getPatch)
                .orElse(null);

        if (patch == null) {
            log.info("파일 전체 diff 미확보(변경 목록에 없거나 patch 없음): {} #{} {}", e.getRepoFullName(), e.getPrNumber(), e.getFilePath());
        }
        return patch;
    }
}
