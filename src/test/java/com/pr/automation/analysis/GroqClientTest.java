package com.pr.automation.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.analysis.github.RepoFileReader;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.config.PrAnalyzerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroqClientTest {

    private static final PrAnalyzerProperties BUDGET =
            new PrAnalyzerProperties(true, "./state.json", 40, 4, 6, 25000);

    private static CommentContext context() {
        return CommentContext.builder()
                .eventType("review_comment").repoFullName("me/repo").prNumber(1)
                .prTitle("t").prBody("b").headSha("sha").filePath("src/Foo.java")
                .line(10).codeContext("diff").commentBody("이거 왜 이렇게 했어요?").build();
    }

    // --- JSON 파싱 동작 (에이전트 경로 공용) ---

    @Test
    void extractJson_순수_JSON() {
        assertThat(GroqClient.extractJson("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void extractJson_코드펜스_제거() {
        assertThat(GroqClient.extractJson("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(GroqClient.extractJson("결과:\n```\n{\"x\":2}\n```\n끝")).isEqualTo("{\"x\":2}");
    }

    @Test
    void extractJson_앞뒤_텍스트_제거() {
        assertThat(GroqClient.extractJson("여기 결과 {\"k\":\"v\"} 입니다")).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void parse_snake_case_JSON을_AnalysisResult로_변환한다() {
        GroqClient client = new GroqClient(null, null, new ObjectMapper(), BUDGET);
        String json = "```json\n"
                + "{\n"
                + "  \"comment_summary\": \"X로 바꾸자는 제안\",\n"
                + "  \"current_approach\": \"B 방식\",\n"
                + "  \"suggested_approach\": \"X 방식\",\n"
                + "  \"verdict\": \"추가 논의 필요 - 성능 측정 후 결정\",\n"
                + "  \"reasoning\": \"두 방식 모두 트레이드오프가 있음\",\n"
                + "  \"suggested_reply\": \"좋은 지적 감사합니다. 측정해보겠습니다.\"\n"
                + "}\n"
                + "```";
        AnalysisResult result = client.parse(json);
        assertThat(result.getCommentSummary()).isEqualTo("X로 바꾸자는 제안");
        assertThat(result.getCurrentApproach()).isEqualTo("B 방식");
        assertThat(result.getSuggestedApproach()).isEqualTo("X 방식");
        assertThat(result.getVerdict()).startsWith("추가 논의 필요");
        assertThat(result.getSuggestedReply()).contains("측정");
    }

    @Test
    void reader가_null이면_REPO_NOT_READABLE_예외() {
        GroqClient client = new GroqClient(null, props(), new ObjectMapper(), BUDGET);

        assertThatThrownBy(() -> client.analyze(context(), null))
                .isInstanceOf(AutomationException.class);
    }

    @Test
    void maxToolIterations가_0이하면_예외() {
        PrAnalyzerProperties zero =
                new PrAnalyzerProperties(true, "./s.json", 40, 0, 6, 25000);
        GroqClient client = new GroqClient(null, props(), new ObjectMapper(), zero);

        assertThatThrownBy(() -> client.analyze(context(), new RecordingReader(Optional.of("x"))))
                .isInstanceOf(AutomationException.class);
    }

    // --- 에이전트 루프 경로 ---

    @Test
    void 시나리오A_read_file_후_submit_analysis로_종료한다() {
        RecordingReader reader = new RecordingReader(Optional.of("public class Foo {}"));
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(eq("/chat/completions"), any(), eq(GroqClient.ChatResponse.class)))
                .thenReturn(
                        responseWithToolCall(GroqClient.class, "read_file", "{\"path\":\"src/Bar.java\"}"),
                        responseWithToolCall(GroqClient.class, "submit_analysis",
                                "{\"comment_summary\":\"요약\",\"current_approach\":\"현행\","
                                        + "\"suggested_approach\":\"제안\",\"verdict\":\"추가 논의 필요 - 근거\","
                                        + "\"reasoning\":\"트레이드오프\",\"suggested_reply\":\"답변\"}"));
        GroqClient client = new GroqClient(rt, props(), new ObjectMapper(), BUDGET);

        AnalysisResult result = client.analyze(context(), reader);

        // src/Foo.java = 시작 파일 프리페치, src/Bar.java = LLM이 자율로 따라간 의존 파일
        assertThat(reader.readPaths).containsExactly("src/Foo.java", "src/Bar.java");
        assertThat(result.getCommentSummary()).isEqualTo("요약");
        assertThat(result.getVerdict()).startsWith("추가 논의 필요");
    }

    @Test
    void 시나리오C_파일이_없으면_도구결과로_알리고_모델이_회복한다() {
        RecordingReader reader = new RecordingReader(Optional.empty());
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(eq("/chat/completions"), any(), eq(GroqClient.ChatResponse.class)))
                .thenReturn(
                        responseWithToolCall(GroqClient.class, "read_file", "{\"path\":\"없는파일.java\"}"),
                        responseWithToolCall(GroqClient.class, "submit_analysis",
                                "{\"comment_summary\":\"요약\",\"verdict\":\"현 구현 유지 권장\"}"));
        GroqClient client = new GroqClient(rt, props(), new ObjectMapper(), BUDGET);

        AnalysisResult result = client.analyze(context(), reader);

        // 프리페치(src/Foo.java)·도구 조회(없는파일.java) 모두 empty여도 모델이 회복해 종료
        assertThat(reader.readPaths).containsExactly("src/Foo.java", "없는파일.java");
        assertThat(result.getVerdict()).isEqualTo("현 구현 유지 권장");
    }

    @Test
    void 시나리오B_예산_내_submit_미호출시_파싱_예외() {
        PrAnalyzerProperties oneRound =
                new PrAnalyzerProperties(true, "./s.json", 40, 1, 6, 25000);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(eq("/chat/completions"), any(), eq(GroqClient.ChatResponse.class)))
                .thenReturn(responseWithToolCall(GroqClient.class, "read_file", "{\"path\":\"a\"}"));
        GroqClient client = new GroqClient(rt, props(), new ObjectMapper(), oneRound);

        assertThatThrownBy(() -> client.analyze(context(), new RecordingReader(Optional.of("x"))))
                .isInstanceOf(AutomationException.class);
    }

    // --- 헬퍼 ---

    private static com.pr.automation.config.GroqProperties props() {
        return new com.pr.automation.config.GroqProperties(
                "key", "http://localhost", "model", 2048, 0.3);
    }

    private static GroqClient.ChatResponse responseWithToolCall(Class<?> ignored, String fn, String argsJson) {
        GroqClient.FunctionCall f = new GroqClient.FunctionCall();
        f.setName(fn);
        f.setArguments(argsJson);
        GroqClient.ToolCall tc = new GroqClient.ToolCall();
        tc.setId("call_1");
        tc.setType("function");
        tc.setFunction(f);
        GroqClient.ChatMessage m = new GroqClient.ChatMessage();
        m.setRole("assistant");
        m.setToolCalls(new ArrayList<>(Collections.singletonList(tc)));
        return new GroqClient.ChatResponse(
                Collections.singletonList(new GroqClient.Choice(m, "tool_calls")));
    }

    private static class RecordingReader implements RepoFileReader {
        final List<String> readPaths = new ArrayList<>();
        private final Optional<String> fileContent;

        RecordingReader(Optional<String> fileContent) {
            this.fileContent = fileContent;
        }

        @Override
        public Optional<String> readFile(String path) {
            readPaths.add(path);
            return fileContent;
        }

        @Override
        public Optional<List<String>> listDirectory(String path) {
            return Optional.of(Arrays.asList("file\tFoo.java (10B)"));
        }
    }
}
