package com.pr.automation.review.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.llm.GroqChatClient;
import com.pr.automation.analysis.llm.dto.ChatMessage;
import com.pr.automation.analysis.llm.dto.FunctionCall;
import com.pr.automation.analysis.llm.dto.ToolCall;
import com.pr.automation.config.PrReviewProperties;
import com.pr.automation.github.GithubClient.ChangedFile;
import com.pr.automation.review.dto.PrReviewEvent;
import com.pr.automation.review.dto.PrReviewResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrReviewPipelineTest {

    private static final PrReviewProperties PROPS =
            new PrReviewProperties(true, "./r.json", 50, 6000, false);

    private static PrReviewEvent event() {
        return PrReviewEvent.builder()
                .deliveryId("d").repoFullName("me/repo").prNumber(7)
                .prTitle("기능 추가").prBody("본문").headSha("sha").prAuthor("me")
                .prHtmlUrl("https://github.com/me/repo/pull/7").build();
    }

    private static List<ChangedFile> files() {
        return Collections.singletonList(ChangedFile.builder()
                .filename("src/Foo.java").status("modified")
                .patch("@@ -1 +1 @@\n+코드").additions(1).deletions(0).build());
    }

    @Test
    void 네_단계를_순서대로_호출하고_최종결과를_종합한다() {
        GroqChatClient chat = mock(GroqChatClient.class);
        when(chat.send(any(), any(), any())).thenReturn(
                findings("[{\"file\":\"src/Foo.java\",\"line\":3,\"severity\":\"높음\",\"category\":\"null-safety\","
                        + "\"title\":\"NPE 위험\",\"detail\":\"널 역참조\",\"suggestion\":\"널 체크\"}]", "언어 총평"),
                findings("[]", "프레임워크 이슈 없음"),
                findings("[]", "보안 이슈 없음"),
                review());
        PrReviewPipeline pipeline = newPipeline(chat);

        PrReviewResult result = pipeline.run(event(), files());

        // 4단계(1~3 findings + 최종 review) 모두 호출
        verify(chat, times(4)).send(any(), any(), any());
        assertThat(result.getOverallSummary()).isEqualTo("전체 요약");
        assertThat(result.getMergedFindings()).hasSize(1);
        assertThat(result.getMergedFindings().get(0).getTitle()).isEqualTo("NPE 위험");
        assertThat(result.getReviewerFocusNotes()).contains("트레이드오프");
        // 단계별 총평 3건이 주입됨
        assertThat(result.getStageSummaries()).hasSize(3);
        assertThat(result.getStageSummaries().get(0)).startsWith("언어:");
    }

    @Test
    void 발견이_없어도_최종결과를_반환한다() {
        GroqChatClient chat = mock(GroqChatClient.class);
        when(chat.send(any(), any(), any())).thenReturn(
                findings("[]", "이슈 없음"),
                findings("[]", "이슈 없음"),
                findings("[]", "이슈 없음"),
                emptyReview());
        PrReviewPipeline pipeline = newPipeline(chat);

        PrReviewResult result = pipeline.run(event(), files());

        assertThat(result.getMergedFindings()).isEmpty();
        assertThat(result.getStageSummaries()).hasSize(3);
    }

    @Test
    void 단계_프롬프트에_diff가_포함된다() {
        GroqChatClient chat = mock(GroqChatClient.class);
        when(chat.send(any(), any(), any())).thenReturn(
                findings("[]", "s"), findings("[]", "s"), findings("[]", "s"), emptyReview());
        PrReviewPipeline pipeline = newPipeline(chat);

        pipeline.run(event(), files());

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chat, times(4)).send(captor.capture(), any(), any());
        // 첫 단계의 user 메시지에 변경 파일 경로가 들어간다
        String firstUserMsg = captor.getAllValues().get(0).get(1).getContent();
        assertThat(firstUserMsg).contains("src/Foo.java");
    }

    // --- 헬퍼 ---

    private static PrReviewPipeline newPipeline(GroqChatClient chat) {
        return new PrReviewPipeline(chat, new PrReviewPromptBuilder(PROPS), new PrReviewToolSpecs(), new ObjectMapper());
    }

    private static ChatMessage findings(String findingsJson, String summary) {
        return assistantWithCall(PrReviewToolSpecs.TOOL_SUBMIT_FINDINGS,
                "{\"findings\":" + findingsJson + ",\"summary\":\"" + summary + "\"}");
    }

    private static ChatMessage review() {
        return assistantWithCall(PrReviewToolSpecs.TOOL_SUBMIT_REVIEW,
                "{\"overall_summary\":\"전체 요약\","
                        + "\"merged_findings\":[{\"file\":\"src/Foo.java\",\"line\":3,\"severity\":\"높음\","
                        + "\"category\":\"null-safety\",\"title\":\"NPE 위험\",\"detail\":\"널 역참조\",\"suggestion\":\"널 체크\"}],"
                        + "\"reviewer_focus_notes\":\"이 트레이드오프를 받아들일지 판단 필요\"}");
    }

    private static ChatMessage emptyReview() {
        return assistantWithCall(PrReviewToolSpecs.TOOL_SUBMIT_REVIEW,
                "{\"overall_summary\":\"이슈 없음\",\"merged_findings\":[],\"reviewer_focus_notes\":\"없음\"}");
    }

    private static ChatMessage assistantWithCall(String fn, String argsJson) {
        FunctionCall f = new FunctionCall();
        f.setName(fn);
        f.setArguments(argsJson);
        ToolCall tc = new ToolCall();
        tc.setId("call_1");
        tc.setType("function");
        tc.setFunction(f);
        ChatMessage m = new ChatMessage();
        m.setRole("assistant");
        m.setToolCalls(new ArrayList<>(Collections.singletonList(tc)));
        return m;
    }
}
