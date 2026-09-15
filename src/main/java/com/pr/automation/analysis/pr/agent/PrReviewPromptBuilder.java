package com.pr.automation.analysis.pr.agent;

import com.pr.automation.github.GithubClient.ChangedFile;
import com.pr.automation.analysis.pr.dto.PrReviewEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

// PR 리뷰 에이전트에게 투입되는 초기 프롬프트 조립
@Component
public class PrReviewPromptBuilder {
    private static final int PR_BODY_LIMIT = 1_500;

    // 초기 프롬프트
    public String buildInitialPrompt(PrReviewEvent event, List<ChangedFile> files) {
        StringBuilder sb = new StringBuilder();
        sb.append(prMeta(event));
        sb.append("\n[변경 내역(diff)]\n").append(renderDiff(files));
        sb.append("\n위 변경 내역을 검토하고, 필요하면 체크아웃된 저장소를 직접 조회한 뒤 지정된 JSON 형식으로 리뷰를 출력하라.\n");
        return sb.toString();
    }

    private String prMeta(PrReviewEvent e) {
        return "[PR 정보]\n"
                + "저장소: " + e.getRepoFullName() + "  PR #" + e.getPrNumber() + "\n"
                + "제목: " + orDash(e.getPrTitle()) + "\n"
                + "설명: " + abbreviate(orDash(e.getPrBody()), PR_BODY_LIMIT) + "\n";
    }

    // 초기 프롬프트에 넣을 diff
    // PR을 작게 나눠 올리는 전제이고, 체크아웃에 변경 전 코드가 없어 잘린 변경은 에이전트가 복원할 수 없기 때문에 전부 넣어줌
    private String renderDiff(List<ChangedFile> files) {
        if (files == null || files.isEmpty()) {
            return "(변경 파일 없음)\n";
        }
        StringBuilder sb = new StringBuilder();
        for (ChangedFile f : files) {
            sb.append("### ").append(f.getFilename())
                    .append(" (").append(f.getStatus())
                    .append(", +").append(f.getAdditions()).append("/-").append(f.getDeletions()).append(")\n");
            if (StringUtils.hasText(f.getPatch())) {
                sb.append("```diff\n").append(f.getPatch()).append("\n```\n");
            } else {
                sb.append("(patch 없음 — 바이너리 또는 대용량 변경)\n");
            }
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
}
