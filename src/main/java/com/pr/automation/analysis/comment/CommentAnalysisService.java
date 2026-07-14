package com.pr.automation.analysis.comment;

import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.github.GithubRepoFileReader;
import com.pr.automation.github.RepoFileReader;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.AsyncConfig;
import com.pr.automation.github.GithubClient;
import com.pr.automation.slack.SlackNotifier;
import com.pr.automation.webhook.recovery.DeliveryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

// 에이전트 루프 실행 서비스단
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentAnalysisService {
    private final GithubClient githubClient;
    private final CommentAnalysisAgent analysisAgent;
    private final SlackNotifier slackNotifier;
    private final CommentStore commentStore;
    private final DeliveryStore deliveryStore;

    // 중복 코멘트는 건너뛰고, 실패는 로그 + Slack 알림으로만 처리
    // 실패 시에도 Slack에 실패 알림 발송
    // deliveryStore.markReceived는 분석 성공 시점에만 호출 — 실패 시 호출하지 않아 recoverer가 재전송할 수 있게 함
    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    public void analyzeAsync(CommentEvent event) {

        // 이미 완료됐거나 다른 스레드가 처리 중이면 즉시 종료 - 단, delivery는 ack 처리
        if (!commentStore.tryClaim(event.getCommentId())) {
            log.info("이미 처리 중이거나 완료된 코멘트, 건너뜀: {} comment={}", event.getRepoFullName(), event.getCommentId());
            deliveryStore.markReceived(event.getDeliveryId());
            return;
        }

        try {
            AnalysisResult result = analyze(event);
            commentStore.markCompleted(event.getCommentId());
            deliveryStore.markReceived(event.getDeliveryId());

            log.info("코멘트 분석 완료: {} #{} comment={} verdict='{}'", event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), result.getVerdict());
        } catch (Exception e) {
            // 실패 시 점유 해제 - 같은 commentId의 재시도를 가능하게 함
            commentStore.release(event.getCommentId());

            // deliveryStore.markReceived 호출하지 않음 — 다음 부팅 시 recoverer가 재전송 트리거
            log.error("코멘트 분석 실패: {} #{} comment={}", event.getRepoFullName(), event.getPrNumber(), event.getCommentId(), e);

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

    // 자율 파일 탐색용 RepoFileReader 구현체( GithubRepoFileReader )를 생성합니다.
    // headSha가 없으면(issue_comment ...) GitHub API로 PR head SHA를 별도 조회하며, 확보 못하면 예외를 던집니다.
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

    // CommentEvent를 CommentContext로 변환합니다.
    // diff hunk 유무에 따라 코드 맥락 문자열을 만들고, 답글이 있으면 부모 코멘트를 조회해 붙이고, 파일 전체 diff를 fetchFilePatch로 채웁니다.
    private CommentContext buildContext(CommentEvent e) {
        StringBuilder code = new StringBuilder();

        if (StringUtils.hasText(e.getDiffHunk())) {
            code.append("코멘트가 달린 지점의 diff (리뷰어가 본 hunk, 코멘트 라인에서 잘림):\n").append(e.getDiffHunk());
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
                .side(e.getSide())
                .startLine(e.getStartLine())
                .originalLine(e.getOriginalLine())
                .codeContext(code.toString())
                .filePatch(fetchFilePatch(e))
                .parentComments(parents)
                .commentBody(e.getCommentBody())
                .build();
    }

    // review_comment이고 파일 경로가 있을 때만 GitHub PR 변경 파일 목록에서 해당 파일의 patch( 전체 diff )를 찾아 반환합니다.
    // 못 찾으면 null이며 분석은 계속 진행됩니다.
    private String fetchFilePatch(CommentEvent e) {
        if (!e.isReviewComment() || !StringUtils.hasText(e.getFilePath()) || !githubClient.isEnabled()) {
            return null;
        }

        String patch = githubClient.fetchPullFiles(e.getRepoFullName(), e.getPrNumber()).stream()
                .filter(f -> e.getFilePath().equals(f.getFilename()))
                .findFirst()
                .map(GithubClient.ChangedFile::getPatch)
                .orElse(null);

        if (patch == null) {
            log.info("파일 전체 diff 미확보(변경 목록에 없거나 patch 없음): {} #{} {}", e.getRepoFullName(), e.getPrNumber(), e.getFilePath());
        }
        return patch;
    }
}
