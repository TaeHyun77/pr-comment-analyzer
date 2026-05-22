package com.pr.automation.analysis.comment;

import com.pr.automation.analysis.agent.CommentAnalysisAgent;
import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.analysis.github.GithubRepoFileReader;
import com.pr.automation.analysis.github.RepoFileReader;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
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
import java.util.List;

// 코멘트 분석 파이프라인: 중복 확인 → 컨텍스트 구성 → 에이전트 실행 → Slack 통지 → 처리 기록
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentAnalysisService {
    private final GithubClient githubClient;
    private final CommentAnalysisAgent analysisAgent;
    private final SlackNotifier slackNotifier;
    private final CommentStore commentStore;

    // 중복 코멘트는 건너뛰고, 실패는 로그 + Slack 알림으로만 처리
    // 실패 시에도 Slack에 실패 알림 발송
    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    public void analyzeAsync(CommentEvent event) {

        // 이미 완료됐거나 다른 스레드가 처리 중이면 즉시 종료
        if (!commentStore.tryClaim(event.getCommentId())) {
            log.info("이미 처리 중이거나 완료된 코멘트, 건너뜀: {} comment={}",
                    event.getRepoFullName(), event.getCommentId());
            return;
        }

        try {
            AnalysisResult result = analyze(event);
            commentStore.markCompleted(event.getCommentId());
            commentStore.save();
            log.info("코멘트 분석 완료: {} #{} comment={} verdict='{}'",
                    event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), result.getVerdict());
        } catch (Exception e) {
            // 실패 시 점유 해제 - 같은 commentId의 재시도를 가능하게 함
            commentStore.release(event.getCommentId());
            log.error("코멘트 분석 실패: {} #{} comment={}",
                    event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), e);
            slackNotifier.sendFailure(event, e);
        }
    }

    // AI 분석 + Slack 알림
    public AnalysisResult analyze(CommentEvent event) {
        CommentContext context = buildContext(event);
        AnalysisResult result = analysisAgent.run(context, repoFileReader(event));
        slackNotifier.send(event, result);

        return result;
    }

    // 자율 탐색 reader를 만듦 -> LLM이 스스로 레포 파일을 뒤져볼 수 있게 해주는 통로 객체 생성
    // headSha가 없으면(issue_comment 등) GitHub API로 PR head SHA를 조회
    // GitHub 비활성이거나 head SHA를 확보할 수 없으면 분석을 진행할 수 없으므로 예외
    private RepoFileReader repoFileReader(CommentEvent e) {
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
        return new GithubRepoFileReader(githubClient, e.getRepoFullName(), headSha);
    }

    private CommentContext buildContext(CommentEvent e) {
        StringBuilder code = new StringBuilder();

        if (StringUtils.hasText(e.getDiffHunk())) {
            code.append("코멘트가 달린 부분의 diff:\n").append(e.getDiffHunk());
        } else {
            code.append("(인라인 코드 없음 — PR 일반 코멘트)");
        }

        List<String> parents = new ArrayList<>();
        if (e.getInReplyToId() != null && githubClient.isEnabled()) {
            githubClient.fetchReviewComment(e.getRepoFullName(), e.getInReplyToId())
                    .ifPresent(parent -> parents.add("@" + parent.getAuthor() + ": " + parent.getBody()));
        }

        return CommentContext.builder()
                .eventType(e.getEventType())
                .repoFullName(e.getRepoFullName())
                .prNumber(e.getPrNumber())
                .prTitle(e.getPrTitle())
                .prBody(e.getPrBody())
                .headSha(e.getHeadSha())
                .filePath(e.getFilePath())
                .line(e.getLine())
                .codeContext(code.toString())
                .parentComments(parents)
                .commentBody(e.getCommentBody())
                .build();
    }
}
