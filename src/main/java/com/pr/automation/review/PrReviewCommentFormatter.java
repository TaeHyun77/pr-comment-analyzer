package com.pr.automation.review;

import com.pr.automation.review.dto.PrReviewResult;
import com.pr.automation.review.dto.ReviewFinding;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// 4단계 파이프라인의 최종 결과를 GitHub PR 코멘트용 md 문자열로 변환합니다.
@Component
public class PrReviewCommentFormatter {
    public String format(PrReviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 자동 PR 리뷰 (4단계)\n\n");
        sb.append("> 언어 → 프레임워크/인프라 → 도메인/보안 → 최종검증 순으로 기계적 이슈를 먼저 정리했습니다. ")
                .append("최종 판단은 리뷰어가 합니다.\n\n");

        sb.append("### 종합 요약\n");
        sb.append(nv(result.getOverallSummary())).append("\n\n");

        sb.append(renderFindings(result.getMergedFindings()));

        sb.append("### 리뷰어가 집중할 본질적 판단 포인트\n");
        sb.append(nv(result.getReviewerFocusNotes())).append("\n\n");

        sb.append(renderStageSummaries(result.getStageSummaries()));
        return sb.toString();
    }

    // 발견된 이슈 목록을 심각도 순으로 정렬해 렌더링
    private String renderFindings(List<ReviewFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "### 확정 이슈\n발견된 기계적 이슈가 없습니다. \n\n";
        }
        List<ReviewFinding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparingInt(f -> severityRank(f.getSeverity())));

        StringBuilder sb = new StringBuilder();
        sb.append("### 확정 이슈 (").append(findings.size()).append("건)\n");
        for (ReviewFinding f : sorted) {
            String location = nv(f.getFile());
            if (f.getLine() != null) {
                location += ":" + f.getLine();
            }
            sb.append("- ").append(severityBadge(f.getSeverity())).append(" **").append(nv(f.getTitle())).append("** — `")
                    .append(location).append("` _(").append(nv(f.getCategory())).append(")_\n");
            if (StringUtils.hasText(f.getDetail())) {
                sb.append("  - ").append(f.getDetail()).append("\n");
            }
            if (StringUtils.hasText(f.getSuggestion())) {
                sb.append("  - 제안: ").append(f.getSuggestion()).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    private String renderStageSummaries(List<String> stageSummaries) {
        if (stageSummaries == null || stageSummaries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<details><summary>단계별 총평</summary>\n\n");
        for (String s : stageSummaries) {
            sb.append("- ").append(s).append("\n");
        }
        sb.append("\n</details>\n");
        return sb.toString();
    }

    private static int severityRank(String severity) {
        if ("높음".equals(severity)) return 0;
        if ("중간".equals(severity)) return 1;
        if ("낮음".equals(severity)) return 2;
        return 3;
    }

    private static String severityBadge(String severity) {
        if ("높음".equals(severity)) return "높음";
        if ("중간".equals(severity)) return "중간";
        if ("낮음".equals(severity)) return "낮음";
        return nv(severity);
    }

    private static String nv(String s) {
        return StringUtils.hasText(s) ? s : "—";
    }
}
