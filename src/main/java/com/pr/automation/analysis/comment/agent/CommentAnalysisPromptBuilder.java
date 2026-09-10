package com.pr.automation.analysis.comment.agent;

import com.pr.automation.analysis.comment.dto.CommentContext;
import com.pr.automation.github.RepoFileReader;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/* 코멘트가 달린 파일 내용과 PR/코멘트 관련 각종 정보를 (크기 상한을 지키며) 에이전트용 초기 유저 프롬프트 문자열로 조립하는 클래스
*
* 코멘트가 달린 파일 본문 확보 및 크기 결정
* 유저 프롬프트 섹션 조립
* 섹션별 렌더링 헬퍼
* 텍스트 가공 유틸
 * */
@Component
@RequiredArgsConstructor
public class CommentAnalysisPromptBuilder {
    private final CommentAnalyzerProperties commentAnalyzerProperties;

    private static final int PR_BODY_LIMIT = 1_500; // PR 본문 설명 길이 제한
    private static final int CODE_LIMIT = 6_000; // 코멘트 주변 코드 맥락 길이 제한
    private static final int FILE_PATCH_LIMIT = 6_000; // 파일 전체 변경 diff 길이 제한
    private static final int PARENT_BODY_LIMIT = 500; // 이전 스레드 코멘트 1건당 길이 제한
    private static final int MAX_THREAD_COMMENTS = 20; // 이전 스레드가 비정상적으로 길 때 토큰 폭주 방지 (오래된 것부터 절단)
    private static final String EVENT_REVIEW_BODY = "review_body";
    private static final String EVENT_COMMIT_COMMENT = "commit_comment";
    private static final int SHORT_SHA_LENGTH = 7;

    // 1. 코멘트가 달린 파일 내용을 본문에 끼워 넣음
    public String buildInitial(
            CommentContext context,
            RepoFileReader reader
    ) {
        String primaryFileContent = null; // 코멘트가 달린 파일 내용
        boolean primaryTruncated = false; // 상한 초과로 일부만 제공됐는지 여부

        // 파일 경로를 가진 이벤트면 모두 본문을 붙인다 - 인라인 코멘트뿐 아니라 커밋 코멘트도 대상 파일이 있다
        if (StringUtils.hasText(context.getFilePath())) {
            String raw = reader.readFile(context.getFilePath()).orElse(null);
            int maxChars = commentAnalyzerProperties.getMaxFileChars();

            if (raw != null && raw.length() > maxChars) {
                primaryTruncated = true;
                // 앞부분만 자르면 뒷부분에 달린 코멘트의 코드가 사각지대가 되므로, 코멘트 라인을 알면 그 주변을 남김
                Integer anchor = anchorLine(context);
                primaryFileContent = anchor != null ? sliceAroundLine(raw, anchor, maxChars) : truncate(raw, maxChars);
            } else {
                primaryFileContent = raw;
            }
        }
        return userPrompt(context, primaryFileContent, primaryTruncated);
    }

    // 코멘트가 달린 파일 본문을 주입, 본문을 못 얻었은 예외 상황이라면 경로만 조사 시작점으로 넘김
    private static void appendPrimaryFile(StringBuilder sb, CommentContext c, String content, boolean truncated) {
        if (!StringUtils.hasText(content)) {
            if (StringUtils.hasText(c.getFilePath())) {
                sb.append("\n[조사 시작점]\n파일 ").append(c.getFilePath())
                        .append(" 부터 살펴보고, 필요한 다른 파일은 도구로 직접 조회해라.\n");
            }
            return;
        }

        sb.append(truncated ? "\n[코멘트가 달린 파일 내용 일부] " : "\n[코멘트가 달린 파일 전체 내용] ")
                .append(c.getFilePath()).append("\n```\n").append(content).append("\n```\n");
        sb.append(truncated
                ? "파일이 길어 코멘트 라인 주변만 제공됐다. 표시 범위 밖 코드가 필요할 때만 도구로 조회해라.\n"
                : "이 파일 전체는 이미 제공됐으므로, 이것만으로 판단 가능하면 추가 조회 없이 바로 결론을 내라.\n"
                        + "이 파일이 호출하는 함수의 정의나 참조하는 설정 파일을 확인해야 할 때만 도구로 조회해라.\n");
    }

    // LLM에 전달할 유저 프롬프트 조립: PR 정보, 코멘트 위치, diff, 부모 스레드, 코멘트 본문, 그리고 코멘트가 달린 파일 본문
    private String userPrompt(CommentContext c, String primaryFileContent, boolean primaryTruncated) {
        StringBuilder sb = new StringBuilder();

        sb.append("[PR 정보]\n")
                .append("저장소: ").append(c.getRepoFullName()).append("  PR #").append(c.getPrNumber()).append('\n')
                .append("제목: ").append(orDash(c.getPrTitle())).append('\n')
                .append("설명: ").append(abbreviate(orDash(c.getPrBody()), PR_BODY_LIMIT)).append('\n')
                .append("\n[코멘트 위치]\n");

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

        sb.append("\n[관련 코드/맥락]\n").append(abbreviate(orDash(c.getCodeContext()), CODE_LIMIT)).append('\n');

        if (StringUtils.hasText(c.getFilePatch())) {
            sb.append("\n[이 파일의 전체 변경 diff]\n")
                    .append("이 PR에서 이 파일에 일어난 모든 변경이다. hunk 헤더 @@ -a,b +c,d @@의 -는 변경 전, +는 변경 후 라인 번호다.\n")
                    .append("```diff\n").append(abbreviate(c.getFilePatch(), FILE_PATCH_LIMIT)).append("\n```\n");
        }

        appendParentThread(sb, c.getParentComments());

        sb.append("\n[분석할 리뷰 코멘트]\n").append(orDash(c.getCommentBody())).append('\n');

        appendPrimaryFile(sb, c, primaryFileContent, primaryTruncated);
        return sb.toString();
    }

    // 답글의 선행 댓글들(오래된→최신 순)을 렌더링
    // 상한 초과 시 오래된 것부터 버리고 생략 개수를 알림
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

    // 라인 번호가 어느 버전(head/base/과거 diff) 기준인지 명시해 잘못된 참조를 막음
    private static void appendLineAnchor(StringBuilder sb, CommentContext c) {
        // 파일 전체 대상 코멘트는 line이 1로 채워져 오므로, 라인 표기를 하면 1번 줄 지적으로 오독된다
        if (c.isFileLevel()) {
            sb.append(" (파일 전체를 대상으로 달린 코멘트 — 특정 라인 지적이 아니다)");
            return;
        }
        // 커밋 코멘트의 라인은 PR 누적 diff가 아니라 그 커밋 하나를 기준으로 매겨진다
        if (EVENT_COMMIT_COMMENT.equals(c.getEventType())) {
            if (c.getLine() != null) {
                sb.append(" (커밋 ").append(shortSha(c.getHeadSha()))
                        .append(" 기준 라인 ").append(c.getLine())
                        .append(" — PR 누적 diff의 라인 번호와 다를 수 있음)");
            }
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
                sb.append(" — 삭제된 코드에 달린 코멘트");
            }
            sb.append(')');
        } else if (c.getOriginalLine() != null) {
            sb.append(" (과거 diff 기준 라인 ").append(c.getOriginalLine()).append(" — 현재 head와 어긋날 수 있음)");
        }
    }

    private static String shortSha(String sha) {
        if (!StringUtils.hasText(sha)) {
            return "?";
        }
        return sha.length() > SHORT_SHA_LENGTH ? sha.substring(0, SHORT_SHA_LENGTH) : sha;
    }

    // 절단 기준 라인, head 기준 line을 우선.
    // 파일 전체 대상 코멘트는 line(=1) 주변으로 자르면 파일 앞머리만 남으므로 앵커 없이 앞에서부터 자르게 한다
    private static Integer anchorLine(CommentContext c) {
        if (c.isFileLevel()) return null;
        if (c.getLine() != null) return c.getLine();
        return c.getOriginalLine();
    }

    // 상한을 넘는 파일을 코멘트 라인 중심으로 잘라, 잘린 범위를 명시해 되돌려줌
    static String sliceAroundLine(String content, int anchorLine, int maxChars) {
        String[] lines = content.split("\n", -1);
        // 파일 끝 개행이 만든 빈 꼬리 요소는 라인으로 세지 않음
        int lineCount = lines.length;
        if (lineCount > 1 && lines[lineCount - 1].isEmpty()) lineCount--;
        int idx = Math.min(Math.max(anchorLine - 1, 0), lineCount - 1);

        // 앵커 라인 하나가 상한을 넘는 극단 케이스는 그 라인만 잘라서 반환
        if (lines[idx].length() >= maxChars) {
            return lines[idx].substring(0, maxChars)
                    + "\n…(전체 " + lineCount + "라인 중 " + (idx + 1) + "라인의 일부만 표시, 코멘트 라인 중심)";
        }

        // 앵커에서 위아래로 번갈아 확장하며 문자 예산 안에서 최대 범위를 잡음
        int start = idx;
        int end = idx;
        int used = lines[idx].length();
        boolean extended = true;
        while (extended) {
            extended = false;
            if (start > 0 && used + lines[start - 1].length() + 1 <= maxChars) {
                start--;
                used += lines[start].length() + 1;
                extended = true;
            }
            if (end < lineCount - 1 && used + lines[end + 1].length() + 1 <= maxChars) {
                end++;
                used += lines[end].length() + 1;
                extended = true;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) sb.append('\n');
            sb.append(lines[i]);
        }
        sb.append("\n…(전체 ").append(lineCount).append("라인 중 ")
                .append(start + 1).append('~').append(end + 1).append("라인만 표시, 코멘트 라인 중심)");
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
