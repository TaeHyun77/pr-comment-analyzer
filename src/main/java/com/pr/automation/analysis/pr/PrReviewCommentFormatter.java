package com.pr.automation.analysis.pr;

import com.pr.automation.llm.dto.LlmUsage;
import com.pr.automation.analysis.pr.dto.PrReviewResult;
import com.pr.automation.analysis.pr.dto.ReviewFinding;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// 에이전트 리뷰 결과를 GitHub PR 코멘트용 md 문자열로 변환합니다.
@Component
public class PrReviewCommentFormatter {
    // 봇이 게시한 리뷰 코멘트 식별용 숨은 마커 - 재진입 시 이 마커의 존재로 게시 여부를 판별해 중복 게시를 막음
    public static final String MARKER = "<!-- pr-automation:pr-review -->";

    public String format(PrReviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER).append('\n');
        sb.append("## 자동 PR 리뷰\n\n");
        sb.append("> diff를 기반으로 관련 코드를 조회하며 이슈를 검토했습니다. ")
                .append("최종 판단은 리뷰어가 합니다.\n\n");

        sb.append("### 종합 요약\n");
        sb.append(nv(result.getOverallSummary())).append("\n\n");

        sb.append(renderFindings(result.getMergedFindings()));

        sb.append("### 리뷰어가 집중할 본질적 판단 포인트\n");
        sb.append(nv(result.getReviewerFocusNotes())).append("\n\n");

        sb.append(renderUsage(result));
        return sb.toString();
    }

    // 사용량은 토큰을 기준으로 표기한다 - 비용은 모델과 요금제에 따라 변하는 파생값이라 PR 간 비교가 어렵다
    private String renderUsage(PrReviewResult result) {
        LlmUsage usage = result.getUsage();
        if (result.getRoundsUsed() == null && usage == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<details><summary>AI 분석 정보</summary>\n\n");
        if (result.getRoundsUsed() != null) {
            sb.append("- 라운드: ").append(result.getRoundsUsed()).append("회\n");
        }
        if (result.getFilesReadCount() != null) {
            sb.append("- 파일 조회: ").append(result.getFilesReadCount()).append("개\n");
        }
        if (usage != null) {
            sb.append("- 토큰: ").append(usage.getTotalTokens())
                    .append(" (입력 ").append(usage.getInputTokens())
                    .append(", 캐시쓰기 ").append(usage.getCacheCreationTokens())
                    .append(", 캐시읽기 ").append(usage.getCacheReadTokens())
                    .append(", 출력 ").append(usage.getOutputTokens()).append(")\n");
            if (usage.getCostUsd() > 0) {
                sb.append("- 추정 비용: $").append(String.format("%.4f", usage.getCostUsd())).append("\n");
            }
        }
        sb.append("\n</details>\n");
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
