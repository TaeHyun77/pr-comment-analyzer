package com.pr.automation.analysis.pr.agent;

import com.pr.automation.analysis.pr.dto.PrReviewEvent;
import com.pr.automation.github.GithubClient.ChangedFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 에이전트는 체크아웃에 변경 전 코드가 없어 잘린 diff를 복원할 수 없으므로, diff는 받은 그대로 전부 넣어야 한다
class PrReviewPromptBuilderTest {

    private static final PrReviewEvent EVENT = PrReviewEvent.builder()
            .repoFullName("me/repo").prNumber(7).prTitle("제목").build();

    private final PrReviewPromptBuilder builder = new PrReviewPromptBuilder();

    @Test
    void 긴_patch도_자르지_않고_전부_넣는다() {
        String longPatch = "@@ -1 +1 @@\n" + repeat("+line\n", 5_000) + "+마지막줄";

        String prompt = builder.buildInitialPrompt(EVENT, Collections.singletonList(file("src/Big.java", longPatch)));

        assertThat(prompt).contains(longPatch);
        assertThat(prompt).doesNotContain("일부만 표시");
    }

    @Test
    void 파일이_많아도_생략하지_않고_전부_넣는다() {
        List<ChangedFile> files = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            files.add(file("src/File" + i + ".java", "@@ -1 +1 @@\n+x"));
        }

        String prompt = builder.buildInitialPrompt(EVENT, files);

        assertThat(prompt).contains("### src/File0.java", "### src/File79.java");
        assertThat(prompt).doesNotContain("생략");
    }

    private static ChangedFile file(String name, String patch) {
        return ChangedFile.builder().filename(name).status("modified").patch(patch).additions(1).deletions(0).build();
    }

    // Java 8 대상이라 String.repeat을 쓸 수 없다
    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
