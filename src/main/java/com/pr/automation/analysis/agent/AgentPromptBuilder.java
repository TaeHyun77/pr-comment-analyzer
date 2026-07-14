package com.pr.automation.analysis.agent;

import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.github.RepoFileReader;
import com.pr.automation.config.PrAnalyzerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// 에이전트에게 줄 프롬프트 정의
@Component
@RequiredArgsConstructor
public class AgentPromptBuilder {
    private static final int PR_BODY_LIMIT = 1_500; // PR 본문 설명 길이 제한
    private static final int CODE_LIMIT = 6_000; // 코멘트 주변 코드 맥락 길이 제한
    private static final int FILE_PATCH_LIMIT = 6_000; // 파일 전체 변경 diff 길이 제한
    private static final String EVENT_REVIEW_COMMENT = "review_comment";

    private final PrAnalyzerProperties prAnalyzerProperties;

    // review_comment이고 filePath가 존재할 때 코멘트가 달린 파일 전체 내용을 본문에 끼워 넣음
    public String buildInitial(
            CommentContext context,
            RepoFileReader reader
    ) {
        String primaryFileContent = null; // 코멘트가 달린 파일 내용

        if (EVENT_REVIEW_COMMENT.equals(context.getEventType()) && StringUtils.hasText(context.getFilePath())) {
            primaryFileContent = reader.readFile(context.getFilePath())
                    .map(c -> truncate(c, prAnalyzerProperties.getMaxFileChars()))
                    .orElse(null);
        }
        return agenticUserPrompt(context, primaryFileContent);
    }

    // LLM에게 전달할 프롬프트 작성
    // 파일 내용만으로 분석이 가능하다면 분석하고, 아니라면 도구를 사용하여 분석
    private String agenticUserPrompt(CommentContext c, String primaryFileContent) {
        StringBuilder sb = new StringBuilder(userPrompt(c)); // PR에 대한 기본적인 정보 프롬프트

        if (StringUtils.hasText(primaryFileContent)) {
            sb.append("\n[코멘트가 달린 파일 전체 내용] ")
                    .append(c.getFilePath()).append('\n').append("```\n")
                    .append(primaryFileContent).append("\n```\n")
                    .append("이 파일 전체는 이미 제공됐으므로, 이것만으로 판단 가능하면 추가 조회 없이 바로 결론을 내라.\n")
                    .append("이 파일이 호출하는 함수의 정의나 참조하는 설정 파일을 확인해야 할 때만 도구로 조회해라.\n");
        } else if (EVENT_REVIEW_COMMENT.equals(c.getEventType()) && StringUtils.hasText(c.getFilePath())) {
            sb.append("\n[조사 시작점]\n파일 ")
                    .append(c.getFilePath())
                    .append(" 부터 살펴보고, 필요한 다른 파일은 도구로 직접 조회해라.\n");
        }
        return sb.toString();
    }

    // PR 정보, 코멘트 위치, diff, 부모 스레드, 코멘트 본문 등등의 내용을 agenticUserPrompt에 전달
    private String userPrompt(CommentContext c) {
        StringBuilder sb = new StringBuilder();

        sb.append("[PR 정보]\n")
                .append("저장소: ").append(c.getRepoFullName()).append("  PR #").append(c.getPrNumber()).append('\n')
                .append("제목: ").append(orDash(c.getPrTitle())).append('\n')
                .append("설명: ").append(abbreviate(orDash(c.getPrBody()), PR_BODY_LIMIT)).append('\n');

        sb.append("\n[코멘트 위치]\n");
        if (EVENT_REVIEW_COMMENT.equals(c.getEventType())) {
            sb.append("파일: ").append(orDash(c.getFilePath()));
            appendLineAnchor(sb, c);
            sb.append('\n');
        } else {
            sb.append("PR 전체에 대한 일반 코멘트\n");
        }

        sb.append("\n[관련 코드/맥락]\n").append(abbreviate(orDash(c.getCodeContext()), CODE_LIMIT)).append('\n');

        if (StringUtils.hasText(c.getFilePatch())) {
            sb.append("\n[이 파일의 전체 변경 diff]\n")
                    .append("이 PR에서 이 파일에 일어난 모든 변경이다. hunk 헤더 @@ -a,b +c,d @@의 -는 변경 전, +는 변경 후 라인 번호다.\n")
                    .append("```diff\n").append(abbreviate(c.getFilePatch(), FILE_PATCH_LIMIT)).append("\n```\n");
        }

        if (c.getParentComments() != null && !c.getParentComments().isEmpty()) {
            sb.append("\n[이전 스레드]\n");
            for (String p : c.getParentComments()) {
                sb.append("- ").append(p).append('\n');
            }
        }

        sb.append("\n[분석할 리뷰 코멘트]\n").append(orDash(c.getCommentBody())).append('\n');
        return sb.toString();
    }

    // 라인 번호가 어느 버전(head/base/과거 diff) 기준인지 명시해 잘못된 참조를 막습니다.
    private static void appendLineAnchor(StringBuilder sb, CommentContext c) {
        if (c.getLine() != null) {
            boolean left = "LEFT".equalsIgnoreCase(c.getSide());
            sb.append(" (").append(left ? "변경 전(base) 기준 " : "변경 후(head) 기준 ");
            if (c.getStartLine() != null) {
                sb.append("라인 ").append(c.getStartLine()).append('~').append(c.getLine());
            } else {
                sb.append("라인 ").append(c.getLine());
            }
            if (left) {
                sb.append(" — 삭제된 코드에 달린 코멘트");
            }
            sb.append(')');
        } else if (c.getOriginalLine() != null) {
            sb.append(" (과거 diff 기준 라인 ").append(c.getOriginalLine()).append(" — 현재 head와 어긋날 수 있음)");
        }
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
