package com.pr.automation.analysis.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.agent.CommentAnalysisAgent;
import com.pr.automation.analysis.comment.dto.AnalysisResult;
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
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

// 에이전트 루프 실행 서비스단
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentAnalysisService {
    private final GithubClient githubClient;
    private final CommentAnalysisAgent analysisAgent;
    private final SlackNotifier slackNotifier;
    private final CommentStore commentStore;
    private final ObjectMapper objectMapper;
    private final RepoCheckoutFactory repoCheckoutFactory;

    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    public void analyzeAsync(CommentEvent event) {
        if (!commentStore.tryClaim(event)) return;

        AnalysisResult result;
        try {
            // 선점 후 이미 결과가 있는 행(ANALYZED)이면 분석은 마치고 통지 단계에서 끊긴 건이므로, 재분석 없이 통지만 재시도
            result = loadSavedResult(event.getCommentId());
            if (result == null) {
                result = runAnalysis(event);
                commentStore.markAnalyzed(event.getCommentId(), serializeResult(result));
            }
        } catch (Exception e) {
            // 원인을 가장 먼저 남긴다 — 뒤따르는 Slack 통지가 꺼져 있거나 실패하면 실패 사유가 어디에도 남지 않는다
            log.error("코멘트 분석 실패: {} #{} comment={}",
                    event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), e);

            // 분석 실패 시, FAILED 상태로 변경 - 재시도 시 lease에 상관 없이 재분석하도록 함
            commentStore.markFailed(event.getCommentId());
            slackNotifier.sendFailure(event, e); // 실패 알림 전송
            return;
        }

        try {
            slackNotifier.send(event, result);
            commentStore.markCompleted(event.getCommentId());
        } catch (Exception e) {
            // 통지만 실패 → 분석 결과(ANALYZED)는 그대로 둬야 재시도 때 재분석을 안 함
            log.warn("통지 실패, 분석 결과는 유지하고 통지만 재시도 대상으로 남김 - comment={}", event.getCommentId(), e);
        }
    }

    // ANALYZED 행의 저장 결과를 복원 / 없거나 깨졌으면 null → 신규 분석
    private AnalysisResult loadSavedResult(long commentId) {
        String json = commentStore.findAnalyzedResult(commentId).orElse(null);
        if (json == null) return null;

        try {
            return objectMapper.readValue(json, AnalysisResult.class);
        } catch (Exception e) {
            log.warn("저장된 분석 결과 역직렬화 실패, 신규 분석으로 진행: comment={}", commentId, e);
            return null;
        }
    }

    // 직렬화 - 실패 시 null 저장
    private String serializeResult(AnalysisResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("분석 결과 직렬화 실패 - 통지 실패 시 재분석됨", e);
            return null;
        }
    }

    // 코멘트가 가리키는 커밋을 체크아웃해 에이전트가 로컬에서 직접 탐색하게 한다
    private AnalysisResult runAnalysis(CommentEvent event) {
        String headSha = resolveHeadSha(event);

        // 체크아웃은 분석 동안만 살아 있으면 된다 — 예외가 나도 close에서 지워진다
        try (RepoCheckout checkout = repoCheckoutFactory.checkout(event.getRepoFullName(), headSha)) {
            return analysisAgent.analyze(buildContext(event), checkout.dir());
        }
    }

    // 코멘트가 가리키는 커밋. 이벤트에 없으면(issue_comment 등) PR의 head를 조회해 채운다
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
        StringBuilder code = new StringBuilder();

        if (StringUtils.hasText(e.getDiffHunk())) {
            code.append("코멘트가 달린 지점의 diff (리뷰어가 본 hunk, 코멘트 라인에서 잘림):\n").append(e.getDiffHunk());
        } else if (e.isReviewBody()) {
            code.append("(인라인 코드 없음 — 리뷰 제출 시 남긴 총평)");
        } else if (e.isCommitComment()) {
            code.append("(인라인 코드 없음 — 커밋에 직접 달린 코멘트. 라인 번호는 PR 전체 diff가 아니라 그 커밋 기준이다)");
        } else {
            code.append("(인라인 코드 없음 — PR 일반 코멘트)");
        }

        List<String> parents = buildParentThread(e);

        return CommentContext.builder()
                .eventType(e.getEventType())
                .repoFullName(e.getRepoFullName())
                .prNumber(e.getPrNumber())
                .prTitle(e.getPrTitle())
                .prBody(e.getPrBody())
                .headSha(e.getHeadSha())
                .filePath(e.getFilePath())
                .line(e.getLine())
                .side(e.getSide())
                .startLine(e.getStartLine())
                .originalLine(e.getOriginalLine())
                .fileLevel(e.isFileLevel())
                .reviewState(e.getReviewState())
                .codeContext(code.toString())
                .filePatch(fetchFilePatch(e))
                .parentComments(parents)
                .commentBody(e.getCommentBody())
                .build();
    }

    // 답글이면 같은 스레드의 선행 코멘트를 시간순으로 모아 프롬프트용 문자열 리스트로 만듭니다.
    // GitHub 리뷰 코멘트는 in_reply_to_id가 항상 스레드 최상위(루트)를 가리키므로, (루트 자신) 또는 (루트를 부모로 갖는 답글) 중 현재 코멘트보다 이전 것만 모읍니다.
    // ( 코멘트 id는 전역 단조 증가하므로 id 오름차순 = 작성 시간순 )
    // 개수/길이 상한은 프롬프트 크기 책임을 갖는 CommentAnalysisPromptBuilder가 적용하므로, 여기서는 자르지 않고 전체를 넘기도록 함
    private List<String> buildParentThread(CommentEvent e) {
        if (e.getInReplyToId() == null || !githubClient.isEnabled()) {
            return new ArrayList<>();
        }
        long rootId = e.getInReplyToId();

        List<GithubClient.FetchedComment> all = githubClient.fetchPullReviewComments(e.getRepoFullName(), e.getPrNumber()).orElse(null);
        if (all == null) {
            // 목록 조회 실패 시 최소한 루트 코멘트라도 확보 (스레드 맥락은 보조 정보이므로 분석은 계속)
            List<String> fallback = new ArrayList<>();
            githubClient.fetchReviewComment(e.getRepoFullName(), rootId)
                    .ifPresent(root -> fallback.add(formatThreadComment(root.getAuthor(), root.getBody())));
            return fallback;
        }

        List<GithubClient.FetchedComment> thread = all.stream()
                .filter(c -> c.getId() == rootId || (c.getInReplyToId() != null && c.getInReplyToId() == rootId))
                .filter(c -> c.getId() < e.getCommentId()) // 현재 코멘트와 그 이후는 제외
                .sorted(Comparator.comparingLong(GithubClient.FetchedComment::getId))
                .collect(Collectors.toCollection(ArrayList::new));

        List<String> parents = new ArrayList<>();
        for (GithubClient.FetchedComment c : thread) {
            parents.add(formatThreadComment(c.getAuthor(), c.getBody()));
        }
        return parents;
    }

    private static String formatThreadComment(String author, String body) {
        return "@" + author + ": " + (body == null ? "" : body);
    }

    // 파일 경로가 있는 코멘트(인라인, 커밋 코멘트)에 대해 GitHub PR 변경 파일 목록에서 해당 파일의 patch( 전체 diff )를 찾아 반환
    // 못 찾으면 null이며 분석은 계속 진행
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
