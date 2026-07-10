package com.pr.automation.analysis.agent;

import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.analysis.github.RepoFileReader;
import com.pr.automation.config.PrAnalyzerProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptBuilderTest {

    private final AgentPromptBuilder builder =
            new AgentPromptBuilder(new PrAnalyzerProperties(true, "./state.json", 8, 6, 25000));

    // 파일 프리페치 없이 프롬프트 본문만 검증하기 위한 빈 reader
    private static final RepoFileReader EMPTY_READER = new RepoFileReader() {
        @Override
        public Optional<String> readFile(String path) {
            return Optional.empty();
        }

        @Override
        public Optional<List<String>> listDirectory(String path) {
            return Optional.empty();
        }
    };

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
    void filePatch가_없으면_전체_변경_diff_섹션을_생략한다() {
        String prompt = builder.buildInitial(reviewContext().line(10).build(), EMPTY_READER);
        assertThat(prompt).doesNotContain("[이 파일의 전체 변경 diff]");
    }

    @Test
    void 긴_filePatch는_잘라서_넣는다() {
        StringBuilder longPatch = new StringBuilder("@@ -1,1 +1,9000 @@\n");
        for (int i = 0; i < 900; i++) {
            longPatch.append("+aaaaaaaa").append(i).append('\n');
        }
        longPatch.append("+TAIL_MARKER");
        String prompt = builder.buildInitial(reviewContext().line(10).filePatch(longPatch.toString()).build(), EMPTY_READER);
        assertThat(prompt).contains("[이 파일의 전체 변경 diff]");
        assertThat(prompt).doesNotContain("TAIL_MARKER");
    }
}
