package com.pr.automation.analysis;

import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.config.AsyncConfig;
import com.pr.automation.github.GithubClient;
import com.pr.automation.slack.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

// 코멘트 분석 파이프라인: 중복 확인 → 컨텍스트 구성 → LLM 분석 → Slack 통지 → 처리 기록
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentAnalysisService {
    private final GithubClient githubClient;
    private final GroqClient groqClient;
    private final SlackNotifier slackNotifier;
    private final ProcessedCommentStore processedCommentStore;

    // 중복 코멘트는 건너뛰고, 실패는 로그 + Slack 알림으로만 처리
    // 실패 시에도 Slack에 실패 알림 발송
    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    public void analyzeAsync(CommentEvent event) {
        // 이미 처리한 이벤트인지 확인
        if (processedCommentStore.isProcessed(event.getCommentId())) {
            log.info("이미 처리한 코멘트, 건너뜀: {} comment={}", event.getRepoFullName(), event.getCommentId());
            return;
        }

        try {
            AnalysisResult result = analyze(event);
            processedCommentStore.markProcessed(event.getCommentId());
            processedCommentStore.save();
            log.info("코멘트 분석 완료: {} #{} comment={} verdict='{}'", event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), result.getVerdict());
        } catch (Exception e) {
            log.error("코멘트 분석 실패: {} #{} comment={}", event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), e);
            slackNotifier.sendFailure(event, e);
        }
    }

    // AI 분석 + Slack 알림
    public AnalysisResult analyze(CommentEvent event) {
        CommentContext context = buildContext(event);
        AnalysisResult result = groqClient.analyze(context);
        slackNotifier.send(event, result);

        return result;
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
                .filePath(e.getFilePath())
                .line(e.getLine())
                .codeContext(code.toString())
                .parentComments(parents)
                .commentBody(e.getCommentBody())
                .build();
    }
}
