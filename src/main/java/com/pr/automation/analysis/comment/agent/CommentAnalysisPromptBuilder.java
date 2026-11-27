package com.pr.automation.analysis.comment.agent;

import com.pr.automation.analysis.comment.dto.CommentContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CommentAnalysisPromptBuilder {
    private static final int PR_BODY_LIMIT = 1_500; // PR 본문 설명 길이 제한
    private static final int FILE_PATCH_LIMIT = 50_000;
    private static final int PARENT_BODY_LIMIT = 500; // 이전 스레드 코멘트 1건당 길이 제한
    private static final int MAX_THREAD_COMMENTS = 20; // 이전 스레드가 비정상적으로 길 때 토큰 폭주 방지 (오래된 것부터 절단)
    private static final String EVENT_REVIEW_BODY = "review_body";

    // 코멘트 1건 분석용 프롬프트 - 파일 본문은 넣지 않는다. 체크아웃에 이미 있으므로 Claude가 필요한 부분만 도구로 읽는다
    public String buildInitial(CommentContext context) {
        return userPrompt(context);
    }

    public String buildBatch(List<CommentContext> contexts) {
        CommentContext first = contexts.get(0);
        StringBuilder sb = new StringBuilder();
        appendPrInfo(sb, first);

        sb.append("\n[리뷰 정보]\n같은 리뷰어가 한 리뷰로 함께 제출한 코멘트 ").append(contexts.size())
                .append("건이다. 각 코멘트를 `코멘트 id=...`로 구분해 모두 분석해라.\n");

        Map<String, String> patchByPath = new LinkedHashMap<>();
        for (CommentContext c : contexts) {
            if (StringUtils.hasText(c.getFilePath())) {
                patchByPath.putIfAbsent(c.getFilePath(), c.getFilePatch());
            }
        }
        for (Map.Entry<String, String> entry : patchByPath.entrySet()) {
            sb.append("\n[").append(entry.getKey()).append(" 의 전체 변경 diff]\n");
            appendPatchBody(sb, entry.getValue());
        }

        sb.append("\n[분석할 코멘트]\n");
        for (CommentContext c : contexts) {
            sb.append("\n### 코멘트 id=").append(c.getCommentId()).append('\n');
            appendLocation(sb, c);
            appendParentThread(sb, c.getParentComments());
            sb.append("[코멘트 본문]\n");
            appendCommentAuthor(sb, c);
            sb.append(orDash(c.getCommentBody())).append('\n');
        }

        if (!patchByPath.isEmpty()) {
            sb.append("\n[조사 시작점]\n코멘트가 달린 파일: ").append(String.join(", ", patchByPath.keySet()))
                    .append("\n파일 내용은 제공되지 않았다. 판정에 필요한 부분만 도구로 읽고, 한 번 읽은 파일은 다른 코멘트에도 재사용해라.\n");
        }
        return sb.toString();
    }

    private static void appendInvestigationStart(StringBuilder sb, CommentContext c) {
        if (StringUtils.hasText(c.getFilePath())) {
            sb.append("\n[조사 시작점]\n파일 ").append(c.getFilePath())
                    .append(" 에 달린 코멘트다. 파일 내용은 제공되지 않았으니 판정에 필요한 부분만 도구로 읽어라.\n");
        }
    }

    // LLM에 전달할 유저 프롬프트 조립: PR 정보, 코멘트 위치, diff, 부모 스레드, 코멘트 본문, 조사 시작점
    private String userPrompt(CommentContext c) {
        StringBuilder sb = new StringBuilder();

        appendPrInfo(sb, c);
        sb.append("\n[코멘트 위치]\n");
        appendLocation(sb, c);

        appendFilePatch(sb, c);

        appendParentThread(sb, c.getParentComments());

        sb.append("\n[분석할 리뷰 코멘트]\n");
        appendCommentAuthor(sb, c);
        sb.append(orDash(c.getCommentBody())).append('\n');

        appendInvestigationStart(sb, c);
        return sb.toString();
    }

    private static void appendPrInfo(StringBuilder sb, CommentContext c) {
        sb.append("[PR 정보]\n")
                .append("저장소: ").append(c.getRepoFullName()).append("  PR #").append(c.getPrNumber()).append('\n')
                .append("작성자: ").append(StringUtils.hasText(c.getPrAuthor()) ? "@" + c.getPrAuthor() : "(없음)").append('\n')
                .append("제목: ").append(orDash(c.getPrTitle())).append('\n')
                .append("설명: ").append(abbreviate(orDash(c.getPrBody()), PR_BODY_LIMIT)).append('\n');
    }

    private static void appendCommentAuthor(StringBuilder sb, CommentContext c) {
        if (!StringUtils.hasText(c.getCommentAuthor())) {
            return;
        }
        boolean isPrAuthor = c.getCommentAuthor().equalsIgnoreCase(c.getPrAuthor());
        sb.append("작성자: @").append(c.getCommentAuthor()).append(isPrAuthor ? " (PR 작성자)" : "").append('\n');
    }

    private static void appendLocation(StringBuilder sb, CommentContext c) {
        if (StringUtils.hasText(c.getFilePath())) {
            sb.append("파일: ").append(c.getFilePath());
            appendLineAnchor(sb, c);
            sb.append('\n');
        } else if (EVENT_REVIEW_BODY.equals(c.getEventType())) {
            sb.append("리뷰 제출 시 남긴 총평 (특정 파일/라인 지적이 아니라 PR 전체 대상)");
            if (StringUtils.hasText(c.getReviewState())) {
                sb.append(" · 리뷰 상태: ").append(c.getReviewState());
            }
            sb.append('\n');
        } else {
            sb.append("PR 전체에 대한 일반 코멘트\n");
        }
    }

    private static void appendFilePatch(StringBuilder sb, CommentContext c) {
        if (!StringUtils.hasText(c.getFilePath())) {
            return;
        }

        sb.append("\n[이 파일의 전체 변경 diff]\n");
        appendPatchBody(sb, c.getFilePatch());
    }

    private static void appendPatchBody(StringBuilder sb, String patch) {
        if (!StringUtils.hasText(patch)) {
            // 조회 실패와 GitHub이 patch를 주지 않은 경우를 구분할 수 없으므로 원인을 단정하지 않는다
            sb.append("확보하지 못했다 — 조회에 실패했거나, 바이너리이거나 변경이 너무 커서 GitHub이 diff를 주지 않았을 수 있다.\n")
                    .append("체크아웃에는 변경 후(head) 코드만 있어 삭제된 코드는 어디서도 확인할 수 없다.\n")
                    .append("삭제된 코드가 판정의 근거가 되어야 한다면 추측하지 말고 확인 불가로 판정해라.\n");
            return;
        }

        sb.append(patch.length() > FILE_PATCH_LIMIT
                        ? "이 파일 변경 중 앞 " + FILE_PATCH_LIMIT + "자만 실었다(전체 " + patch.length()
                                + "자). 뒤쪽 변경이 잘려 있으니 다른 변경이 없다고 단정하지 마라.\n"
                        : "이 PR에서 이 파일에 일어난 모든 변경이다.\n")
                .append("hunk 헤더 @@ -a,b +c,d @@의 -는 변경 전, +는 변경 후 라인 번호다.\n")
                .append("```diff\n").append(abbreviate(patch, FILE_PATCH_LIMIT)).append("\n```\n");
    }

    private static void appendParentThread(StringBuilder sb, List<String> parents) {
        if (parents == null || parents.isEmpty()) {
            return;
        }
        sb.append("\n[이전 스레드]\n");
        int omitted = parents.size() - MAX_THREAD_COMMENTS;
        if (omitted > 0) {
            sb.append("- …(오래된 코멘트 ").append(omitted).append("개 생략)\n");
            parents = parents.subList(omitted, parents.size());
        }
        for (String p : parents) {
            sb.append("- ").append(abbreviate(p, PARENT_BODY_LIMIT)).append('\n');
        }
    }

    private static void appendLineAnchor(StringBuilder sb, CommentContext c) {
        if (c.isFileLevel()) {
            sb.append(" (파일 전체를 대상으로 달린 코멘트 — 특정 라인 지적이 아니다)");
            return;
        }
        if (c.getLine() != null) {
            boolean left = "LEFT".equalsIgnoreCase(c.getSide());
            sb.append(" (").append(left ? "변경 전(base) 기준 " : "변경 후(head) 기준 ");
            if (c.getStartLine() != null) {
                sb.append("라인 ").append(c.getStartLine()).append('~').append(c.getLine());
            } else {
                sb.append("라인 ").append(c.getLine());
            }
            if (left) {
                // 삭제된 줄은 체크아웃에 없으므로 파일 diff에서 찾는 방법을 함께 알려 준다
                sb.append(" — 삭제된 코드에 달린 코멘트, 파일 diff에서 변경 전(-) 줄 번호로 찾는다");
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
}
