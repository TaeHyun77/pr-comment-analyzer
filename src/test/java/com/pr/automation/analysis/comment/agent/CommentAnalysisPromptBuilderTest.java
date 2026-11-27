package com.pr.automation.analysis.comment.agent;

import com.pr.automation.analysis.comment.dto.CommentContext;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class CommentAnalysisPromptBuilderTest {

    private final CommentAnalysisPromptBuilder builder = new CommentAnalysisPromptBuilder();

    private CommentContext.CommentContextBuilder reviewContext() {
        return CommentContext.builder()
                .eventType("review_comment")
                .repoFullName("me/repo")
                .prNumber(7)
                .prTitle("제목")
                .prBody("본문")
                .headSha("sha1")
                .filePath("src/Foo.java")
                .commentBody("이렇게 하면 어떨까요?");
    }

    @Test
    void 라인번호는_변경후_기준임을_명시한다() {
        String prompt = builder.buildInitial(reviewContext().line(10).build());
        assertThat(prompt).contains("변경 후(head) 기준 라인 10");
    }

    @Test
    void side가_LEFT면_변경전_기준과_삭제된_코드임을_명시한다() {
        String prompt = builder.buildInitial(reviewContext().line(88).side("LEFT").build());
        assertThat(prompt).contains("변경 전(base) 기준 라인 88");
        assertThat(prompt).contains("삭제된 코드");
    }

    @Test
    void 멀티라인_코멘트는_라인_범위로_표기한다() {
        String prompt = builder.buildInitial(reviewContext().startLine(5).line(10).build());
        assertThat(prompt).contains("라인 5~10");
    }

    @Test
    void 파일_전체_대상_코멘트는_line이_1이어도_라인_지적으로_표기하지_않는다() {
        String prompt = builder.buildInitial(reviewContext().fileLevel(true).line(1).build());
        assertThat(prompt).contains("파일 전체를 대상으로 달린 코멘트");
        assertThat(prompt).doesNotContain("기준 라인 1");
    }

    @Test
    void 파일_경로가_없는_일반_코멘트는_PR_전체_대상으로_표기한다() {
        String prompt = builder.buildInitial(
                CommentContext.builder().eventType("issue_comment").repoFullName("me/repo").prNumber(7)
                        .commentBody("좋아 보입니다").build());
        assertThat(prompt).contains("PR 전체에 대한 일반 코멘트");
        assertThat(prompt).doesNotContain("[조사 시작점]");
    }

    @Test
    void line이_없으면_originalLine을_과거_diff_기준으로_표기한다() {
        String prompt = builder.buildInitial(reviewContext().originalLine(7).build());
        assertThat(prompt).contains("과거 diff 기준 라인 7");
        assertThat(prompt).contains("어긋날 수 있음");
    }

    @Test
    void filePatch가_있으면_전체_변경_diff_섹션을_넣는다() {
        String patch = "@@ -1,3 +1,4 @@\n+x\n@@ -50,2 +51,3 @@\n+y";
        String prompt = builder.buildInitial(reviewContext().line(10).filePatch(patch).build());
        assertThat(prompt).contains("[이 파일의 전체 변경 diff]");
        assertThat(prompt).contains("```diff\n" + patch + "\n```");
        // hunk 헤더 읽는 법 안내
        assertThat(prompt).contains("변경 전, +");
    }

    @Test
    void filePatch가_없으면_확보하지_못했음을_명시한다() {
        String prompt = builder.buildInitial(reviewContext().line(10).build());
        assertThat(prompt).contains("[이 파일의 전체 변경 diff]");
        assertThat(prompt).contains("확보하지 못했다");
        // 근거가 없는데 단정하지 않도록 판정 지침까지 함께 전달돼야 한다
        assertThat(prompt).contains("확인 불가로 판정해라");
        assertThat(prompt).doesNotContain("모든 변경이다");
    }

    @Test
    void 파일이_없는_코멘트는_diff_섹션_자체를_만들지_않는다() {
        String prompt = builder.buildInitial(
                CommentContext.builder()
                        .eventType("issue_comment")
                        .repoFullName("me/repo")
                        .prNumber(7)
                        .commentBody("전반적으로 좋네요")
                        .build());
        assertThat(prompt).doesNotContain("[이 파일의 전체 변경 diff]");
    }

    @Test
    void 상한_이하_filePatch는_전체임을_명시한다() {
        String patch = "@@ -1,3 +1,4 @@\n+x";
        String prompt = builder.buildInitial(reviewContext().line(10).filePatch(patch).build());
        assertThat(prompt).contains("이 PR에서 이 파일에 일어난 모든 변경이다");
        assertThat(prompt).doesNotContain("자만 실었다");
    }

    @Test
    void 파일_코멘트는_파일_본문_대신_조사_시작점만_안내한다() {
        // 파일 본문은 체크아웃에 있으므로 미리 넣지 않고, Claude가 필요한 부분만 읽게 한다
        String prompt = builder.buildInitial(reviewContext().line(80).build());

        assertThat(prompt).contains("[조사 시작점]", "src/Foo.java", "파일 내용은 제공되지 않았으니");
        assertThat(prompt).doesNotContain("[코멘트가 달린 파일");
    }

    @Test
    void 상한을_넘는_filePatch는_잘라_넣고_잘렸음을_명시한다() {
        StringBuilder longPatch = new StringBuilder("@@ -1,1 +1,60000 @@\n");
        for (int i = 0; i < 6_000; i++) {
            longPatch.append("+aaaaaaaaaaaaaaaaaaaa").append(i).append('\n');
        }
        longPatch.append("+TAIL_MARKER");
        String prompt = builder.buildInitial(reviewContext().line(10).filePatch(longPatch.toString()).build());

        assertThat(prompt).contains("[이 파일의 전체 변경 diff]");
        assertThat(prompt).doesNotContain("TAIL_MARKER");
        // 잘렸는데 "모든 변경"이라고 단언하면 모델이 다른 변경이 없다고 오판한다
        assertThat(prompt).contains("자만 실었다");
        assertThat(prompt).doesNotContain("이 PR에서 이 파일에 일어난 모든 변경이다");
    }

    @Test
    void 실측_분포의_상위값인_2만자_patch는_잘리지_않는다() {
        StringBuilder patch = new StringBuilder("@@ -1,1 +1,2000 @@\n");
        for (int i = 0; i < 2_000; i++) {
            patch.append("+aaaaaa").append(i).append('\n');
        }
        patch.append("+TAIL_MARKER");
        String prompt = builder.buildInitial(reviewContext().line(10).filePatch(patch.toString()).build());

        assertThat(prompt).contains("TAIL_MARKER");
        assertThat(prompt).contains("이 PR에서 이 파일에 일어난 모든 변경이다");
    }

    // ---- 묶음 프롬프트 ----

    @Test
    void 묶음_프롬프트는_모든_코멘트를_id로_구분하고_같은_파일_diff는_한_번만_싣는다() {
        String patch = "@@ -1,3 +1,4 @@\n+unique-patch-line";
        CommentContext a = reviewContext().commentId(701L).line(10).filePatch(patch).commentBody("첫 코멘트").build();
        CommentContext b = reviewContext().commentId(702L).startLine(20).line(22).filePatch(patch).commentBody("둘째 코멘트").build();
        CommentContext body = CommentContext.builder().commentId(9000L).eventType("review_body").repoFullName("me/repo").prNumber(7)
                .reviewState("commented").commentBody("총평입니다").build();

        String prompt = builder.buildBatch(java.util.Arrays.asList(a, b, body));

        assertThat(prompt).contains("코멘트 id=701", "코멘트 id=702", "코멘트 id=9000");
        assertThat(prompt).contains("첫 코멘트", "둘째 코멘트", "총평입니다");
        assertThat(prompt).contains("변경 후(head) 기준 라인 10", "라인 20~22", "리뷰 제출 시 남긴 총평");
        // 같은 파일에 달린 코멘트가 여럿이어도 diff는 한 번만
        assertThat(prompt.split("unique-patch-line", -1)).hasSize(2);
        assertThat(prompt).containsOnlyOnce("[PR 정보]");
    }

    // ---- PR 작성자 표시 ----

    @Test
    void 분석_대상_코멘트의_작성자를_표시하고_PR_작성자면_표시를_붙인다() {
        String byAuthor = builder.buildInitial(reviewContext().line(10).prAuthor("myname").commentAuthor("myname").build());
        String byReviewer = builder.buildInitial(reviewContext().line(10).prAuthor("myname").commentAuthor("reviewer").build());

        assertThat(byAuthor).contains("작성자: @myname (PR 작성자)");
        assertThat(byReviewer).contains("작성자: @reviewer\n").doesNotContain("@reviewer (PR 작성자)");
        // PR 정보에도 작성자를 밝혀 스레드의 표시와 대조할 수 있게 한다
        assertThat(byReviewer).contains("[PR 정보]").contains("작성자: @myname\n");
    }

    @Test
    void 묶음_프롬프트에도_코멘트별_작성자와_PR_작성자_표시가_들어간다() {
        CommentContext mine = reviewContext().commentId(701L).line(10).prAuthor("myname").commentAuthor("myname").build();
        CommentContext other = reviewContext().commentId(702L).line(12).prAuthor("myname").commentAuthor("reviewer").build();

        String prompt = builder.buildBatch(java.util.Arrays.asList(mine, other));

        assertThat(prompt).contains("작성자: @myname (PR 작성자)", "작성자: @reviewer\n");
    }
}
