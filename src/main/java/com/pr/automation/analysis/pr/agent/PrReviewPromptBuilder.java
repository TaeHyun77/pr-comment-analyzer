package com.pr.automation.analysis.pr.agent;

import com.pr.automation.config.properties.PrReviewProperties;
import com.pr.automation.github.GithubClient.ChangedFile;
import com.pr.automation.analysis.pr.dto.PrReviewEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * PR 리뷰 에이전트에게 투입되는 초기 프롬프트 조립
 */
@Component
@RequiredArgsConstructor
public class PrReviewPromptBuilder {
    private static final int PR_BODY_LIMIT = 1_500;

    private final PrReviewProperties properties;

    public String buildInitialPrompt(PrReviewEvent event, List<ChangedFile> files) {
        StringBuilder sb = new StringBuilder();
        sb.append(prMeta(event));
        sb.append("\n[변경 내역(diff)]\n").append(renderDiff(files));
        sb.append("\n위 변경 내역을 검토하고, 필요하면 read_file/list_directory로 관련 코드를 추가 조회한 뒤 submit_review로 리뷰를 제출하라.\n");
        return sb.toString();
    }

    private String prMeta(PrReviewEvent e) {
        return "[PR 정보]\n"
                + "저장소: " + e.getRepoFullName() + "  PR #" + e.getPrNumber() + "\n"
                + "제목: " + orDash(e.getPrTitle()) + "\n"
                + "설명: " + abbreviate(orDash(e.getPrBody()), PR_BODY_LIMIT) + "\n";
    }

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
