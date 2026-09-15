package com.pr.automation.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.config.properties.ClaudeCliProperties;
import com.pr.automation.error.AutomationException;
import com.pr.automation.llm.dto.ChatMessage;
import com.pr.automation.llm.dto.LlmResponse;
import com.pr.automation.llm.dto.LlmUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재시도 / 사용량 파싱 / 실행 커맨드 검증.
 * 실제 subprocess 대신 execute()를 오버라이드한 서브클래스를 사용.
 */
class LlmChatClientRetryTest {

    private static final Path WORK_DIR = Paths.get(".");

    private AtomicInteger sleepCount;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        sleepCount = new AtomicInteger();
        objectMapper = new ObjectMapper();
    }

    @Test
    void 정상응답은_한_번에_반환된다() {
        TestClient client = new TestClient(props(3), objectMapper, sleepCount, Arrays.asList("분석 결과입니다."));

        String content = client.runAgent("지시서", "프롬프트", WORK_DIR).getMessage().getContent();

        assertThat(content).isEqualTo("분석 결과입니다.");
        assertThat(client.callCount).isEqualTo(1);
        assertThat(sleepCount.get()).isZero();
    }

    @Test
    void 일시_오류는_재시도해서_성공한다() {
        TestClient client = new TestClient(props(3), objectMapper, sleepCount, Arrays.asList(null, null, "성공"));

        String content = client.runAgent("지시서", "프롬프트", WORK_DIR).getMessage().getContent();

        assertThat(content).isEqualTo("성공");
        assertThat(client.callCount).isEqualTo(3);
        // 실패한 두 번 뒤에만 대기하고, 마지막 성공 뒤에는 대기하지 않는다
        assertThat(sleepCount.get()).isEqualTo(2);
    }

    @Test
    void 재시도를_모두_소진하면_예외를_던진다() {
        TestClient client = new TestClient(props(3), objectMapper, sleepCount, Arrays.asList(null, null, null));

        assertThatThrownBy(() -> client.runAgent("지시서", "프롬프트", WORK_DIR))
                .isInstanceOf(AutomationException.class);
        assertThat(client.callCount).isEqualTo(3);
    }

    @Test
    void 사용량은_total_cost_usd와_modelUsage_전체를_합산한다() throws Exception {
        TestClient client = new TestClient(props(3), objectMapper, sleepCount, Arrays.asList());
        // 실제 claude -p --output-format json 응답 형태
        String cliJson = "{"
                + "\"result\":\"ok\",\"is_error\":false,\"duration_ms\":1706,\"total_cost_usd\":0.0758,"
                + "\"usage\":{\"input_tokens\":515,\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0,\"output_tokens\":4},"
                + "\"modelUsage\":{"
                + "\"claude-sonnet-5\":{\"inputTokens\":515,\"outputTokens\":4,"
                + "\"cacheReadInputTokens\":10,\"cacheCreationInputTokens\":20},"
                + "\"claude-haiku-4-5\":{\"inputTokens\":901,\"outputTokens\":9,"
                + "\"cacheReadInputTokens\":0,\"cacheCreationInputTokens\":0}}}";

        LlmUsage usage = client.extractResult(cliJson).getUsage();

        // 응답에 cost_usd 필드는 없다 - total_cost_usd를 읽어야 0이 아닌 값이 나온다
        assertThat(usage.getCostUsd()).isEqualTo(0.0758);
        assertThat(usage.getDurationMs()).isEqualTo(1706L);
        // usage 블록만 보면 CLI가 내부 보조 작업에 쓰는 모델(haiku 901토큰)이 누락된다
        assertThat(usage.getInputTokens()).isEqualTo(1416L);
        assertThat(usage.getTotalTokens()).isEqualTo(1459L);
    }

    @Test
    void modelUsage가_없으면_usage_블록으로_폴백한다() throws Exception {
        TestClient client = new TestClient(props(3), objectMapper, sleepCount, Arrays.asList());
        String cliJson = "{\"result\":\"ok\",\"total_cost_usd\":0.01,"
                + "\"usage\":{\"input_tokens\":100,\"cache_creation_input_tokens\":200,"
                + "\"cache_read_input_tokens\":300,\"output_tokens\":5}}";

        assertThat(client.extractResult(cliJson).getUsage().getTotalTokens()).isEqualTo(605L);
    }

    @Test
    void 실행_커맨드는_읽기전용_도구와_격리_플래그를_포함한다() {
        TestClient client = new TestClient(props(3), objectMapper, sleepCount, Arrays.asList());

        List<String> command = client.buildNativeCommand("분석 지시서");

        assertThat(command).containsSequence("--model", "sonnet");
        assertThat(command).containsSequence("--effort", "high");
        // 체크아웃에 빌드 스크립트가 있으므로 Bash 계열이 섞이면 안 된다
        assertThat(command).containsSequence("--tools", "Read Grep Glob");
        // 비용 상한에 걸리면 탐색 도중 결과 없이 끝나 분석 전체를 잃으므로 걸지 않는다
        assertThat(command).doesNotContain("--max-budget-usd");
        // 호스트 개인 설정(CLAUDE.md/플러그인/MCP)이 프롬프트에 섞이지 않도록 하는 핵심 플래그
        assertThat(command).contains("--safe-mode", "--strict-mcp-config", "--no-session-persistence");
        assertThat(command).containsSequence("--system-prompt", "분석 지시서");
    }

    @Test
    void 시스템_프롬프트가_비어도_CLI_기본_프롬프트로_되돌아가지_않는다() {
        TestClient client = new TestClient(props(3), objectMapper, sleepCount, Arrays.asList());

        List<String> command = client.buildNativeCommand("  ");

        int idx = command.indexOf("--system-prompt");
        assertThat(idx).isNotNegative();
        assertThat(command.get(idx + 1)).isNotBlank();
    }

    // ---- 헬퍼 ----

    private static ClaudeCliProperties props(int maxAttempts) {
        return new ClaudeCliProperties(maxAttempts, "sonnet", "high", "Read Grep Glob");
    }

    /** execute()를 오버라이드해 실제 subprocess 없이 동작. outputs에 null이면 그 순서에서 예외를 던짐. */
    private static class TestClient extends LlmChatClient {
        private final List<String> outputs;
        int callCount = 0;
        private final AtomicInteger sleepCounter;

        TestClient(ClaudeCliProperties props, ObjectMapper objectMapper,
                   AtomicInteger sleepCounter, List<String> outputs) {
            super(props, objectMapper);
            this.outputs = outputs;
            this.sleepCounter = sleepCounter;
        }

        @Override
        protected LlmResponse execute(String systemPrompt, String prompt, Path workingDir) throws ClaudeCliException {
            int idx = callCount++;
            if (idx >= outputs.size() || outputs.get(idx) == null) {
                throw new ClaudeCliException("시뮬레이션 오류 (시도 " + (idx + 1) + ")");
            }
            return new LlmResponse(ChatMessage.assistant(outputs.get(idx)), LlmUsage.zero());
        }

        @Override
        protected void sleepWithFullJitter(long maxMillis) {
            sleepCounter.incrementAndGet();
        }
    }
}
