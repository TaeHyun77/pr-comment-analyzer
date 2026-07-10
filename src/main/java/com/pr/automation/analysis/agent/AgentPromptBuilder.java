package com.pr.automation.analysis.agent;

import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.analysis.github.RepoFileReader;
import com.pr.automation.config.PrAnalyzerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// 테스트
/**
 * 에이전트 루프에 투입되는 초기 프롬프트를 생성 ( review_comment일 때만 )
 * [ PR 메타 + 코멘트 위치/본문 코멘트 파일 ...]
 */
@Component
@RequiredArgsConstructor
public class AgentPromptBuilder {
    private static final int PR_BODY_LIMIT = 1_500; // PR 본문 설명 길이 제한
    private static final int CODE_LIMIT = 6_000; // 코멘트 주변 코드 맥락 길이 제한
    private static final String EVENT_REVIEW_COMMENT = "review_comment";

    private final PrAnalyzerProperties prAnalyzerProperties;

    // 코멘트가 달린 파일을 미리 한 번 읽어 본문에 끼워 넣음 ( review_comment + filePath 있을 때만 )
    public String buildInitial(CommentContext context, RepoFileReader reader) {
        String primaryFileContent = null;

        if (EVENT_REVIEW_COMMENT.equals(context.getEventType()) && StringUtils.hasText(context.getFilePath())) {
            primaryFileContent = reader.readFile(context.getFilePath())
                    .map(c -> truncate(c, prAnalyzerProperties.getMaxFileChars()))
                    .orElse(null);
        }
        return agenticUserPrompt(context, primaryFileContent);
    }

    private String agenticUserPrompt(CommentContext c, String primaryFileContent) {
        StringBuilder sb = new StringBuilder(userPrompt(c));

        if (StringUtils.hasText(primaryFileContent)) {
            sb.append("\n[코멘트가 달린 파일 전체 내용] ").append(c.getFilePath()).append('\n')
                    .append("```\n").append(primaryFileContent).append("\n```\n")
                    .append("이 파일 전체는 이미 제공됐다. 이 안에서 판단 가능하면 추가 조회 없이 바로 결론을 내라.\n")
                    .append("이 파일이 호출하는 함수의 정의나 참조하는 설정 파일을 확인해야 할 때만 도구로 조회해라.\n");
        } else if (EVENT_REVIEW_COMMENT.equals(c.getEventType()) && StringUtils.hasText(c.getFilePath())) {
            sb.append("\n[조사 시작점]\n파일 ").append(c.getFilePath())
                    .append(" 부터 살펴보고, 필요한 다른 파일은 도구로 직접 조회해라.\n");
        }
        return sb.toString();
    }

    // PR 정보, 코멘트 위치, diff, 부모 스레드, 코멘트 본문 등등
    private String userPrompt(CommentContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append("[PR 정보]\n")
                .append("저장소: ").append(c.getRepoFullName()).append("  PR #").append(c.getPrNumber()).append('\n')
                .append("제목: ").append(orDash(c.getPrTitle())).append('\n')
                .append("설명: ").append(abbreviate(orDash(c.getPrBody()), PR_BODY_LIMIT)).append('\n');

        sb.append("\n[코멘트 위치]\n");
        if (EVENT_REVIEW_COMMENT.equals(c.getEventType())) {
            sb.append("파일: ").append(orDash(c.getFilePath()));
            if (c.getLine() != null) sb.append(" (라인 ").append(c.getLine()).append(')');
            sb.append('\n');
        } else {
            sb.append("PR 전체에 대한 일반 코멘트\n");
        }

        sb.append("\n[관련 코드/맥락]\n").append(abbreviate(orDash(c.getCodeContext()), CODE_LIMIT)).append('\n');

        if (c.getParentComments() != null && !c.getParentComments().isEmpty()) {
            sb.append("\n[이전 스레드]\n");
            for (String p : c.getParentComments()) {
                sb.append("- ").append(p).append('\n');
            }
        }

        sb.append("\n[분석할 리뷰 코멘트]\n").append(orDash(c.getCommentBody())).append('\n');
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
        return s.substring(0, max) + "\n…(파일 일부만 표시, 총 " + s.length() + "자)";
    }
}
