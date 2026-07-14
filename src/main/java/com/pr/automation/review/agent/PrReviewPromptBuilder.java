package com.pr.automation.review.agent;

import com.pr.automation.config.PrReviewProperties;
import com.pr.automation.github.GithubClient.ChangedFile;
import com.pr.automation.review.dto.PrReviewEvent;
import com.pr.automation.review.dto.ReviewFinding;
import com.pr.automation.review.dto.StageReviewResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 4단계 리뷰의 각 단계에 투입되는 사용자 프롬프트를 조립
 */
@Component
@RequiredArgsConstructor
public class PrReviewPromptBuilder {
    private static final int PR_BODY_LIMIT = 1_500;

    private final PrReviewProperties properties;

    // 1~3단계용 프롬프트: diff와 이전 단계 발견을 동반
    public String buildStagePrompt(PrReviewEvent event, List<ChangedFile> files, List<StageReviewResult> priorResults) {
        StringBuilder sb = new StringBuilder();
        sb.append(prMeta(event));
        sb.append("\n[변경 내역(diff)]\n").append(renderDiff(files));

        if (priorResults != null && !priorResults.isEmpty()) {
            sb.append("\n[이전 단계 발견 — 중복 보고 금지]\n").append(renderPriorBrief(priorResults));
        }

        sb.append("\n위 변경 내역을 이 단계의 관점에서만 검토하고 submit_findings로 제출해라.\n");
        return sb.toString();
    }

    // 4단계용 프롬프트: 1~3단계 발견 상세를 종합 대상으로 제공
    public String buildFinalPrompt(PrReviewEvent event, List<StageReviewResult> stageResults) {
        StringBuilder sb = new StringBuilder();
        sb.append(prMeta(event));
        sb.append("\n[1~3단계 발견 상세 — 이것을 종합·검증하라]\n").append(renderStageDetail(stageResults));
        sb.append("\n위 발견들을 종합·중복제거·오탐제거하고 submit_review로 제출해라.\n");
        return sb.toString();
    }

    // 저장소/PR 번호/제목/설명을 담은 공통 PR 정보 블록을 만듦
    private String prMeta(PrReviewEvent e) {
        return "[PR 정보]\n"
                + "저장소: " + e.getRepoFullName() + "  PR #" + e.getPrNumber() + "\n"
                + "제목: " + orDash(e.getPrTitle()) + "\n"
                + "설명: " + abbreviate(orDash(e.getPrBody()), PR_BODY_LIMIT) + "\n";
    }

    // 변경 파일 목록을 파일별로 ### 파일명 (상태, +추가/-삭제) 헤더와 diff 코드블록으로 렌더링
    private String renderDiff(List<ChangedFile> files) {
        if (files == null || files.isEmpty()) {
            return "(변경 파일 없음)\n";
        }
        int maxFiles = properties.getMaxFiles();
        int maxPatchChars = properties.getMaxPatchChars();
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(files.size(), maxFiles);
        for (int i = 0; i < shown; i++) {
            ChangedFile f = files.get(i);
            sb.append("### ").append(f.getFilename())
                    .append(" (").append(f.getStatus())
                    .append(", +").append(f.getAdditions()).append("/-").append(f.getDeletions()).append(")\n");
            if (StringUtils.hasText(f.getPatch())) {
                sb.append("```diff\n").append(truncate(f.getPatch(), maxPatchChars)).append("\n```\n");
            } else {
                sb.append("(patch 없음 — 바이너리 또는 대용량 변경)\n");
            }
        }
        if (files.size() > maxFiles) {
            sb.append("\n…(파일 ").append(files.size() - maxFiles)
                    .append("개는 분량 제한으로 생략됨. 총 ").append(files.size()).append("개 변경)\n");
        }
        return sb.toString();
    }

    private String renderPriorBrief(List<StageReviewResult> priorResults) {
        StringBuilder sb = new StringBuilder();
        for (StageReviewResult r : priorResults) {
            if (r.getFindings() == null || r.getFindings().isEmpty()) {
                continue;
            }
            for (ReviewFinding f : r.getFindings()) {
                sb.append("- [").append(r.getStageName()).append("] ")
                        .append(orDash(f.getTitle()))
                        .append(" (").append(orDash(f.getFile())).append(")\n");
            }
        }
        return sb.length() == 0 ? "(이전 단계 발견 없음)\n" : sb.toString();
    }

    private String renderStageDetail(List<StageReviewResult> stageResults) {
        StringBuilder sb = new StringBuilder();
        for (StageReviewResult r : stageResults) {
            sb.append("## ").append(r.getStageName()).append(" 단계\n");
            sb.append("총평: ").append(orDash(r.getSummary())).append("\n");
            if (r.getFindings() == null || r.getFindings().isEmpty()) {
                sb.append("(발견 없음)\n\n");
                continue;
            }
            for (ReviewFinding f : r.getFindings()) {
                sb.append("- [").append(orDash(f.getSeverity())).append("] ")
                        .append(orDash(f.getTitle()))
                        .append(" · 파일: ").append(orDash(f.getFile()));
                if (f.getLine() != null) {
                    sb.append(":").append(f.getLine());
                }
                sb.append("\n  분류: ").append(orDash(f.getCategory()))
                        .append("\n  설명: ").append(orDash(f.getDetail()))
                        .append("\n  제안: ").append(orDash(f.getSuggestion()))
                        .append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String orDash(String s) {
        return StringUtils.hasText(s) ? s : "(없음)";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(없음)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n…(patch 일부만 표시, 총 " + s.length() + "자)";
    }
}
