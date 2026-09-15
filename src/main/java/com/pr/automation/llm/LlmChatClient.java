package com.pr.automation.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.llm.dto.ChatMessage;
import com.pr.automation.llm.dto.LlmResponse;
import com.pr.automation.llm.dto.LlmUsage;
import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import com.pr.automation.config.properties.ClaudeCliProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * claude -p 서브프로세스를 사용하는 LLM 클라이언트
 * HTTP API 대신 로컬 Claude Code CLI를 호출하며, 서브프로세스 오류 시 maxAttempts 회 재시도합니다.
 * 실행 시간과 비용에는 상한을 두지 않으며, 탐색 중 강제 종료되어 분석 결과를 잃는 것을 방지합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmChatClient {
    private static final long INITIAL_BACKOFF_MS = 1000L;

    /**
     * 실행 호스트의 개인 설정이 프롬프트로 새어드는 것을 차단하는 역할
     * safe-mode: CLAUDE.md, 스킬, 플러그인, 훅, MCP를 비활성화 - bare 모드와 달리 구독 인증은 그대로 동작합니다.
     * 도구 접근 범위는 작업 디렉터리(체크아웃)로 제한되고, 쓸 수 있는 도구는 --tools로 읽기 전용만 허용합니다.
     */
    private static final List<String> NATIVE_ISOLATION_FLAGS = Collections.unmodifiableList(Arrays.asList(
            "--safe-mode",
            "--strict-mcp-config",
            "--no-session-persistence"));

    // 시스템 프롬프트가 비어 있어도 CLI 기본 프롬프트로 되돌아가지 않게 하는 최소 대체값
    private static final String FALLBACK_SYSTEM_PROMPT = "조사를 마치면 JSON으로만 응답한다.";

    private final ClaudeCliProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 네이티브 도구 모드로 한 번 호출하고 결과 텍스트를 돌려줍니다.
     * 라운드 루프와 도구 프로토콜을 CLI가 맡으므로 호출자는 프롬프트와 작업 디렉터리만 넘깁니다.
     */
    public LlmResponse runAgent(String systemPrompt, String userPrompt, Path workingDir) {
        int attempt = 0;
        long backoffMillis = INITIAL_BACKOFF_MS;
        int maxAttempts = properties.getMaxAttempts();

        while (attempt < maxAttempts) {
            try {
                return execute(systemPrompt, userPrompt, workingDir);
            } catch (ClaudeCliException e) {
                backoffMillis = handleRetry(++attempt, backoffMillis, e.getMessage(), e);
            }
        }
        throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "알 수 없는 오류로 claude 호출 실패");
    }

    // 체크아웃 디렉터리를 작업 경로로 삼아 CLI가 직접 파일을 탐색하게 합니다.
    protected LlmResponse execute(String systemPrompt, String prompt, Path workingDir) throws ClaudeCliException {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("pr-analyzer-", ".txt");
            Files.write(tempFile, prompt.getBytes(StandardCharsets.UTF_8));

            ProcessBuilder pb = new ProcessBuilder(buildNativeCommand(systemPrompt));
            pb.directory(workingDir.toFile());
            pb.redirectInput(tempFile.toFile());

            Process process = pb.start();

            AtomicReference<String> stdoutRef = new AtomicReference<>("");
            AtomicReference<IOException> stdoutErrorRef = new AtomicReference<>();
            Thread stdoutReader = new Thread(() -> {
                try {
                    stdoutRef.set(readStream(process.getInputStream()));
                } catch (IOException e) {
                    stdoutErrorRef.set(e);
                }
            }, "claude-stdout");

            // stderr는 파이프 버퍼 초과 시 프로세스가 블록되지 않도록 소비만 함
            Thread stderrReader = new Thread(() -> {
                try {
                    readStream(process.getErrorStream());
                } catch (IOException ignored) {}
            }, "claude-stderr");

            stdoutReader.start();
            stderrReader.start();
            stdoutReader.join();
            stderrReader.join(3_000);

            if (stdoutErrorRef.get() != null) {
                throw new ClaudeCliException("stdout 읽기 실패: " + stdoutErrorRef.get().getMessage(), stdoutErrorRef.get());
            }

            int exitCode = process.waitFor();
            String stdout = stdoutRef.get();

            if (exitCode != 0) {
                log.error("claude 종료 코드={} 출력={}", exitCode, abbreviate(stdout, 300));
                throw new ClaudeCliException("claude 종료 코드=" + exitCode);
            }

            return extractResult(stdout);

        } catch (ClaudeCliException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ClaudeCliException("subprocess 실행 실패: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 읽기 전용 내장 도구만 허용한 실행 커맨드
     * 패키지 접근 - 읽기 전용 도구와 격리 플래그가 빠지지 않았는지 테스트에서 검증
     */
    List<String> buildNativeCommand(String systemPrompt) {
        List<String> command = new ArrayList<>(Arrays.asList(
                "claude", "-p", "--output-format", "json",
                "--model", properties.getModel(),
                "--effort", properties.getEffort(),
                "--tools", properties.getAllowedTools()));
        command.addAll(NATIVE_ISOLATION_FLAGS);
        command.add("--system-prompt");
        command.add(StringUtils.hasText(systemPrompt) ? systemPrompt : FALLBACK_SYSTEM_PROMPT);
        return command;
    }

    // --output-format json 래퍼에서 result와 사용량을 꺼냅니다. - JSON 파싱 실패 시 raw 출력 반환
    LlmResponse extractResult(String stdout) throws ClaudeCliException {
        try {
            JsonNode node = objectMapper.readTree(stdout);
            if (node.has("is_error") && node.get("is_error").asBoolean()) {
                String msg = node.has("result") ? node.get("result").asText() : "알 수 없음";
                throw new ClaudeCliException("claude 오류 응답: " + msg);
            }
            if (node.has("result")) {
                return new LlmResponse(ChatMessage.assistant(node.get("result").asText()), toUsage(node));
            }
            throw new ClaudeCliException("claude 응답에 result 없음: " + abbreviate(stdout, 200));
        } catch (JsonProcessingException e) {
            log.warn("claude 출력 JSON 파싱 실패, raw 출력 사용. 출력={}", abbreviate(stdout, 200));
            return new LlmResponse(ChatMessage.assistant(stdout), LlmUsage.zero());
        }
    }

    // 토큰은 usage가 아니라 modelUsage를 합산 - usage는 --model로 지정한 주 모델만 담고 있어,
    // CLI가 내부 보조 작업에 쓰는 다른 모델(haiku 등)의 사용량이 누락될 수 있기 때문
    private static LlmUsage toUsage(JsonNode node) {
        long input = 0L;
        long cacheCreation = 0L;
        long cacheRead = 0L;
        long output = 0L;

        JsonNode modelUsage = node.get("modelUsage");
        if (modelUsage != null && modelUsage.isObject() && modelUsage.size() > 0) {
            for (JsonNode m : modelUsage) {
                input += m.path("inputTokens").asLong(0L);
                cacheCreation += m.path("cacheCreationInputTokens").asLong(0L);
                cacheRead += m.path("cacheReadInputTokens").asLong(0L);
                output += m.path("outputTokens").asLong(0L);
            }
        } else {
            // modelUsage가 없는 응답 형태를 대비한 폴백 (주 모델 사용량만 확보)
            JsonNode usage = node.path("usage");
            input = usage.path("input_tokens").asLong(0L);
            cacheCreation = usage.path("cache_creation_input_tokens").asLong(0L);
            cacheRead = usage.path("cache_read_input_tokens").asLong(0L);
            output = usage.path("output_tokens").asLong(0L);
        }

        return new LlmUsage(input, cacheCreation, cacheRead, output,
                node.path("duration_ms").asLong(0L),
                node.path("total_cost_usd").asDouble(0.0));
    }

    // ---- 응답 파싱 및 재시도 ----

    private long handleRetry(int attempt, long currentBackoff, String causeMsg, Exception e) {
        int maxAttempts = properties.getMaxAttempts();
        if (attempt >= maxAttempts) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR,
                    "claude 재시도 횟수 초과: " + causeMsg, e);
        }
        log.warn("claude 호출 실패: {}, 최대 {}ms 내외 대기 후 재시도 ({}/{})", causeMsg, currentBackoff, attempt, maxAttempts);
        sleepWithFullJitter(currentBackoff);
        return currentBackoff * 2;
    }

    protected void sleepWithFullJitter(long maxMillis) {
        try {
            long jitter = ThreadLocalRandom.current().nextLong(maxMillis + 1);
            Thread.sleep(jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AutomationException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AI_API_ERROR,
                    "재시도 대기 중 인터럽트 발생");
        }
    }

    // ---- 유틸 ----

    private static String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(없음)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // 서브프로세스 실행 실패를 나타내는 내부 예외
    static class ClaudeCliException extends Exception {
        ClaudeCliException(String message) { super(message); }
        ClaudeCliException(String message, Throwable cause) { super(message, cause); }
    }
}
