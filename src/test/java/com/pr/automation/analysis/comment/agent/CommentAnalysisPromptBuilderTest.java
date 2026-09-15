package com.pr.automation.analysis.comment.agent;

import com.pr.automation.analysis.comment.dto.CommentContext;
import com.pr.automation.github.RepoFileReader;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CommentAnalysisPromptBuilderTest {

    private final CommentAnalysisPromptBuilder builder =
            new CommentAnalysisPromptBuilder(new CommentAnalyzerProperties(true, 25000, true, 25, 3, 20));

    // 파일 프리페치 없이 프롬프트 본문만 검증하기 위한 빈 reader
    private static final RepoFileReader EMPTY_READER = path -> Optional.empty();

    private CommentContext.CommentContextBuilder reviewContext() {
        return CommentContext.builder()
                .eventType("review_comment")
                .repoFullName("me/repo")
                .prNumber(7)
                .prTitle("제목")
                .prBody("본문")
                .headSha("sha1")
                .filePath("src/Foo.java")
                .codeContext("코멘트가 달린 지점의 diff:\n@@ -1,3 +1,4 @@\n+x")
                .commentBody("이렇게 하면 어떨까요?");
    }

    @Test
    void 라인번호는_변경후_기준임을_명시한다() {
        String prompt = builder.buildInitial(reviewContext().line(10).build(), EMPTY_READER);
        assertThat(prompt).contains("변경 후(head) 기준 라인 10");
    }

    @Test
    void side가_LEFT면_변경전_기준과_삭제된_코드임을_명시한다() {
        String prompt = builder.buildInitial(reviewContext().line(88).side("LEFT").build(), EMPTY_READER);
        assertThat(prompt).contains("변경 전(base) 기준 라인 88");
        assertThat(prompt).contains("삭제된 코드");
    }

    @Test
    void 멀티라인_코멘트는_라인_범위로_표기한다() {
        String prompt = builder.buildInitial(reviewContext().startLine(5).line(10).build(), EMPTY_READER);
        assertThat(prompt).contains("라인 5~10");
    }

    @Test
    void 파일_전체_대상_코멘트는_line이_1이어도_라인_지적으로_표기하지_않는다() {
        String prompt = builder.buildInitial(reviewContext().fileLevel(true).line(1).build(), EMPTY_READER);
        assertThat(prompt).contains("파일 전체를 대상으로 달린 코멘트");
        assertThat(prompt).doesNotContain("기준 라인 1");
    }

    @Test
    void 커밋_코멘트도_파일_경로와_본문이_프롬프트에_들어간다() {
        RepoFileReader reader = path -> Optional.of("package a;\nclass Foo {}\n");
        String prompt = builder.buildInitial(
                reviewContext().eventType("commit_comment").headSha("deadbeef1234").line(11).build(), reader);

        assertThat(prompt).contains("파일: src/Foo.java");
        assertThat(prompt).contains("커밋 deadbee 기준 라인 11");
        assertThat(prompt).contains("[코멘트가 달린 파일 전체 내용] src/Foo.java");
        // 파일 정보가 있는데 "PR 전체에 대한 일반 코멘트"로 뭉개지면 안 된다
        assertThat(prompt).doesNotContain("PR 전체에 대한 일반 코멘트");
    }

    @Test
    void 커밋_코멘트는_본문을_못_읽어도_조사_시작점으로_파일을_알려준다() {
        String prompt = builder.buildInitial(
                reviewContext().eventType("commit_comment").headSha("deadbeef1234").line(11).build(), EMPTY_READER);
        assertThat(prompt).contains("[조사 시작점]");
        assertThat(prompt).contains("src/Foo.java");
    }

    @Test
    void 파일_경로가_없는_일반_코멘트는_PR_전체_대상으로_표기한다() {
        String prompt = builder.buildInitial(
                CommentContext.builder().eventType("issue_comment").repoFullName("me/repo").prNumber(7)
                        .commentBody("좋아 보입니다").build(), EMPTY_READER);
        assertThat(prompt).contains("PR 전체에 대한 일반 코멘트");
        assertThat(prompt).doesNotContain("[조사 시작점]");
    }

    @Test
    void line이_없으면_originalLine을_과거_diff_기준으로_표기한다() {
        String prompt = builder.buildInitial(reviewContext().originalLine(7).build(), EMPTY_READER);
        assertThat(prompt).contains("과거 diff 기준 라인 7");
        assertThat(prompt).contains("어긋날 수 있음");
    }

    @Test
    void filePatch가_있으면_전체_변경_diff_섹션을_넣는다() {
        String patch = "@@ -1,3 +1,4 @@\n+x\n@@ -50,2 +51,3 @@\n+y";
        String prompt = builder.buildInitial(reviewContext().line(10).filePatch(patch).build(), EMPTY_READER);
        assertThat(prompt).contains("[이 파일의 전체 변경 diff]");
        assertThat(prompt).contains("```diff\n" + patch + "\n```");
        // hunk 헤더 읽는 법 안내
        assertThat(prompt).contains("변경 전, +");
    }

    @Test
    void filePatch가_없으면_확보하지_못했음을_명시한다() {
        String prompt = builder.buildInitial(reviewContext().line(10).build(), EMPTY_READER);
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
                        .build(),
                EMPTY_READER);
        assertThat(prompt).doesNotContain("[이 파일의 전체 변경 diff]");
    }

    @Test
    void 상한_이하_filePatch는_전체임을_명시한다() {
        String patch = "@@ -1,3 +1,4 @@\n+x";
        String prompt = builder.buildInitial(reviewContext().line(10).filePatch(patch).build(), EMPTY_READER);
        assertThat(prompt).contains("이 PR에서 이 파일에 일어난 모든 변경이다");
        assertThat(prompt).doesNotContain("자만 실었다");
    }

    // --- 프리페치 파일 절단 전략 ---

    // 100라인짜리 파일(라인당 "LINE_001" 형식)을 돌려주는 reader
    private static RepoFileReader hundredLineReader() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            sb.append(String.format("LINE_%03d", i)).append('\n');
        }
        String content = sb.toString();
        return path -> Optional.of(content);
    }

    @Test
    void 상한을_넘는_파일은_코멘트_라인_중심으로_잘라_넣는다() {
        // maxFileChars=200 → 100라인(약 900자) 파일은 코멘트 라인(80) 주변만 남아야 함
        CommentAnalysisPromptBuilder small =
                new CommentAnalysisPromptBuilder(new CommentAnalyzerProperties(true, 200, true, 25, 3, 20));
        String prompt = small.buildInitial(reviewContext().line(80).build(), hundredLineReader());

        assertThat(prompt).contains("LINE_080");
        assertThat(prompt).doesNotContain("LINE_001");
        assertThat(prompt).contains("전체 100라인 중");
        // 일부만 제공됐음을 정직하게 알리고, "전체 제공" 문구는 빠져야 함
        assertThat(prompt).contains("코멘트 라인 주변만");
        assertThat(prompt).doesNotContain("이 파일 전체는 이미 제공됐으므로");
    }

    @Test
    void 상한_이하_파일은_전체를_그대로_넣는다() {
        String prompt = builder.buildInitial(reviewContext().line(80).build(), hundredLineReader());

        assertThat(prompt).contains("LINE_001");
        assertThat(prompt).contains("LINE_100");
        assertThat(prompt).contains("이 파일 전체는 이미 제공됐으므로");
    }

    @Test
    void 라인_정보가_없으면_앞부분_절단으로_폴백한다() {
        CommentAnalysisPromptBuilder small =
                new CommentAnalysisPromptBuilder(new CommentAnalyzerProperties(true, 200, true, 25, 3, 20));
        String prompt = small.buildInitial(reviewContext().build(), hundredLineReader());

        assertThat(prompt).contains("LINE_001");
        assertThat(prompt).doesNotContain("LINE_100");
    }

    @Test
    void 상한을_넘는_filePatch는_잘라_넣고_잘렸음을_명시한다() {
        StringBuilder longPatch = new StringBuilder("@@ -1,1 +1,60000 @@\n");
        for (int i = 0; i < 6_000; i++) {
            longPatch.append("+aaaaaaaaaaaaaaaaaaaa").append(i).append('\n');
        }
        longPatch.append("+TAIL_MARKER");
        String prompt = builder.buildInitial(reviewContext().line(10).filePatch(longPatch.toString()).build(), EMPTY_READER);

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
        String prompt = builder.buildInitial(reviewContext().line(10).filePatch(patch.toString()).build(), EMPTY_READER);

        assertThat(prompt).contains("TAIL_MARKER");
        assertThat(prompt).contains("이 PR에서 이 파일에 일어난 모든 변경이다");
    }
}
