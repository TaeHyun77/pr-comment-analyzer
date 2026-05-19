package com.pr.automation.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.analysis.github.RepoFileReader;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.GroqProperties;
import com.pr.automation.config.PrAnalyzerProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class GroqClient {

    private static final String SYSTEM_AGENTIC = loadPrompt("prompts/agentic-system.md");
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // Limits
    private static final int PR_BODY_LIMIT = 1_500;
    private static final int CODE_LIMIT = 6_000;

    // Tools
    private static final String TOOL_READ_FILE = "read_file";
    private static final String TOOL_LIST_DIR = "list_directory";
    private static final String TOOL_SUBMIT = "submit_analysis";

    // Roles
    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";

    private final RestTemplate groqRestTemplate;
    private final GroqProperties groqProperties;
    private final ObjectMapper objectMapper;
    private final PrAnalyzerProperties prAnalyzerProperties;

    /**
     * 상태 관리를 위한 내부 레코드/클래스
     * 에이전트 루프의 변수들을 하나로 캡슐화하여 메서드 간 전달을 쉽게 만듭니다.
     */
    private static class AgentState {
        final List<ChatMessage> messages = new ArrayList<>();
        final List<String> exploredFiles = new ArrayList<>();
        int filesReadCount = 0;
        boolean forceNext = false;
    }

    public AnalysisResult analyze(CommentContext context, RepoFileReader reader) {
        if (reader == null) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REPO_NOT_READABLE, "reader 미제공");
        }
        if (prAnalyzerProperties.getMaxToolIterations() <= 0) {
            throw new AutomationException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AI_API_ERROR, "max-tool-iterations가 0 이하로 설정되어 분석 불가");
        }
        return analyzeAgentic(context, reader);
    }

    private AnalysisResult analyzeAgentic(CommentContext context, RepoFileReader reader) {
        int maxRounds = prAnalyzerProperties.getMaxToolIterations();
        AgentState state = new AgentState();

        // 1. 초기 메시지 세팅 (프리페치 포함)
        state.messages.add(new ChatMessage(ROLE_SYSTEM, SYSTEM_AGENTIC));
        state.messages.add(new ChatMessage(ROLE_USER, buildInitialUserPrompt(context, reader)));

        if (log.isDebugEnabled()) {
            try {
                log.debug("LLM 입력 messages={}", objectMapper.writeValueAsString(state.messages));
            } catch (Exception ignore) {
                // 디버그용 직렬화 실패는 무시
            }
        }

        // 2. 에이전트 루프 실행
        for (int round = 0; round < maxRounds; round++) {
            boolean isLastRound = (round == maxRounds - 1);
            boolean forceSubmit = state.forceNext || isLastRound;

            // 환각 방어: 강제 제출 상황이면 엄격한 룰 추가
            if (forceSubmit && isLastRound) {
                state.messages.add(new ChatMessage(ROLE_USER,
                        "더 이상 파일을 조회할 예산이 없습니다. 반드시 지금까지 확보한 정보만으로 'submit_analysis' 도구를 호출해 결론을 제출하세요. 알 수 없는 내용은 억지로 지어내지 말고 '확인 불가'로 명시하세요."));
            }

            ChatRequest request = buildChatRequest(state.messages, forceSubmit);
            ChatMessage responseMessage = firstMessage(callApi(request));
            if (log.isDebugEnabled()) {
                try {
                    log.debug("LLM 응답 round={} forceSubmit={} message={}",
                            round, forceSubmit, objectMapper.writeValueAsString(responseMessage));
                } catch (Exception ignore) {
                    // 디버그용 직렬화 실패는 무시
                }
            }
            List<ToolCall> calls = responseMessage.getToolCalls();

            // 도구를 사용하지 않은 경우 처리
            if (calls == null || calls.isEmpty()) {
                if (forceSubmit) {
                    AnalysisResult fallbackResult = parseFallback(responseMessage.getContent());
                    logResult("폴백 파싱", fallbackResult);
                    return fallbackResult;
                }
                state.messages.add(assistantText(responseMessage.getContent()));
                state.messages.add(new ChatMessage(ROLE_USER, "일반 텍스트로 답하지 마세요. 반드시 도구를 호출해야 합니다."));
                state.forceNext = true;
                continue;
            }

            // 도구 실행 및 분석 완료 여부 체크
            state.messages.add(assistantWithCalls(responseMessage));
            AnalysisResult finalResult = executeToolCalls(calls, state, reader);

            if (finalResult != null) {
                logResult("submit_analysis", finalResult);
                log.info("에이전트 분석 완료. 조회 파일 {}개: {}", state.exploredFiles.size(), state.exploredFiles);
                return finalResult;
            }
        }

        throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, "에이전트 루프 예산 내 최종 제출 실패");
    }

    // 도구 호출을 순회하며 실행하고 결과를 상태에 반영
    private AnalysisResult executeToolCalls(List<ToolCall> calls, AgentState state, RepoFileReader reader) {
        AnalysisResult submittedResult = null;
        int maxFiles = prAnalyzerProperties.getMaxFiles();
        int maxFileChars = prAnalyzerProperties.getMaxFileChars();

        for (ToolCall tc : calls) {
            String fnName = tc.getFunction() != null ? tc.getFunction().getName() : "";
            String args = tc.getFunction() != null ? tc.getFunction().getArguments() : "{}";
            String toolResultString;

            switch (fnName) {
                case TOOL_SUBMIT:
                    submittedResult = parseSubmit(args);
                    toolResultString = "분석이 성공적으로 제출되었습니다.";
                    break;

                case TOOL_READ_FILE:
                    String filePath = argPath(args);
                    if (!StringUtils.hasText(filePath)) {
                        toolResultString = "오류: path 인자가 제공되지 않았습니다.";
                    } else if (state.filesReadCount >= maxFiles) {
                        toolResultString = "경고: 파일 조회 예산(" + maxFiles + "개)이 소진되었습니다. 지금까지의 정보로 분석을 제출하세요.";
                    } else {
                        state.filesReadCount++;
                        state.exploredFiles.add(filePath);
                        toolResultString = reader.readFile(filePath)
                                .map(c -> truncate(c, maxFileChars))
                                .orElse("오류: 파일 없음 또는 읽을 수 없는 파일 형식 (" + filePath + ")");
                    }
                    break;

                case TOOL_LIST_DIR:
                    String dirPath = argPath(args);
                    String targetDir = dirPath == null ? "" : dirPath;
                    toolResultString = reader.listDirectory(targetDir)
                            .map(list -> list.isEmpty() ? "(빈 디렉터리)" : String.join("\n", list))
                            .orElse("오류: 디렉터리를 찾을 수 없음 (" + targetDir + ")");
                    break;

                default:
                    toolResultString = "오류: 알 수 없는 도구 호출 (" + fnName + ")";
                    break;
            }
            state.messages.add(ChatMessage.tool(tc.getId(), toolResultString));
        }
        return submittedResult;
    }

    private ChatRequest buildChatRequest(List<ChatMessage> messages, boolean forceSubmit) {
        return new ChatRequest(
                groqProperties.getModel(),
                messages,
                groqProperties.getTemperature(),
                groqProperties.getMaxTokens(),
                buildTools(forceSubmit),
                forceSubmit ? forcedChoice() : "auto"
        );
    }

    private String buildInitialUserPrompt(CommentContext context, RepoFileReader reader) {
        String primaryFileContent = null;
        if ("review_comment".equals(context.getEventType()) && StringUtils.hasText(context.getFilePath())) {
            primaryFileContent = reader.readFile(context.getFilePath())
                    .map(c -> truncate(c, prAnalyzerProperties.getMaxFileChars()))
                    .orElse(null);
        }
        return agenticUserPrompt(context, primaryFileContent);
    }

    private ChatResponse callApi(ChatRequest request) {
        try {
            return groqRestTemplate.postForObject("/chat/completions", request, ChatResponse.class);
        } catch (RestClientException e) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, e);
        }
    }

    private ChatMessage firstMessage(ChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()
                || response.getChoices().get(0).getMessage() == null) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "AI API의 빈 응답");
        }
        return response.getChoices().get(0).getMessage();
    }

    private AnalysisResult parseSubmit(String arguments) {
        try {
            return objectMapper.readValue(StringUtils.hasText(arguments) ? arguments : "{}", AnalysisResult.class);
        } catch (JsonProcessingException e) {
            log.warn("submit_analysis 인자 파싱 실패. 인자: {}", abbreviate(arguments, 500));
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, e);
        }
    }

    private AnalysisResult parseFallback(String content) {
        if (!StringUtils.hasText(content)) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, "빈 텍스트 응답");
        }
        String json = extractJson(content);
        try {
            return objectMapper.readValue(json, AnalysisResult.class);
        } catch (JsonProcessingException e) {
            log.warn("텍스트 폴백 응답 파싱 실패. 응답: {}", abbreviate(content, 500));
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, e);
        }
    }

    // 디버그용: 최종 분석 결과 전체 내용을 로그로 남긴다(경로 = submit_analysis | 폴백 파싱)
    private void logResult(String path, AnalysisResult result) {
        if (!log.isDebugEnabled()) {
            return;
        }
        try {
            log.debug("분석 결과({})={}", path, objectMapper.writeValueAsString(result));
        } catch (Exception ignore) {
            // 디버그용 직렬화 실패는 무시
        }
    }

    private String argPath(String arguments) {
        if (!StringUtils.hasText(arguments)) return null;
        try {
            JsonNode node = objectMapper.readTree(arguments).get("path");
            return node != null && !node.isNull() ? node.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n…(파일 일부만 표시, 총 " + s.length() + "자)";
    }

    static String extractJson(String content) {
        String trimmed = content.trim();
        Matcher m = CODE_FENCE.matcher(trimmed);
        if (m.find()) {
            return m.group(1).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    // 프롬프트 생성부

    private String agenticUserPrompt(CommentContext c, String primaryFileContent) {
        StringBuilder sb = new StringBuilder(userPrompt(c));
        if (StringUtils.hasText(primaryFileContent)) {
            sb.append("\n[코멘트가 달린 파일 전체 내용] ").append(c.getFilePath()).append('\n')
                    .append("```\n").append(primaryFileContent).append("\n```\n")
                    .append("이 파일 전체는 이미 제공됐다. 이 안에서 판단 가능하면 추가 조회 없이 바로 결론을 내라.\n")
                    .append("이 파일이 호출하는 함수의 정의나 참조하는 설정 파일을 확인해야 할 때만 도구로 조회해라.\n");
        } else if ("review_comment".equals(c.getEventType()) && StringUtils.hasText(c.getFilePath())) {
            sb.append("\n[조사 시작점]\n파일 ").append(c.getFilePath())
                    .append(" 부터 살펴보고, 필요한 다른 파일은 도구로 직접 조회해라.\n");
        }
        return sb.toString();
    }

    private String userPrompt(CommentContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append("[PR 정보]\n")
                .append("저장소: ").append(c.getRepoFullName()).append("  PR #").append(c.getPrNumber()).append('\n')
                .append("제목: ").append(orDash(c.getPrTitle())).append('\n')
                .append("설명: ").append(abbreviate(orDash(c.getPrBody()), PR_BODY_LIMIT)).append('\n');

        sb.append("\n[코멘트 위치]\n");
        if ("review_comment".equals(c.getEventType())) {
            sb.append("파일: ").append(orDash(c.getFilePath()));
            if (c.getLine() != null) sb.append(" (라인 ").append(c.getLine()).append(')');
            sb.append('\n');
        } else {
            sb.append("PR 전체에 대한 일반 코멘트\n");
        }

        sb.append("\n[관련 코드/맥락]\n").append(abbreviate(orDash(c.getCodeContext()), CODE_LIMIT)).append('\n');

        if (c.getParentComments() != null && !c.getParentComments().isEmpty()) {
            sb.append("\n[이전 스레드]\n");
            for (String p : c.getParentComments()) {
                sb.append("- ").append(p).append('\n');
            }
        }

        sb.append("\n[분석할 리뷰 코멘트]\n").append(orDash(c.getCommentBody())).append('\n');
        return sb.toString();
    }

    private static String orDash(String s) {
        return StringUtils.hasText(s) ? s : "(없음)";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(없음)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String loadPrompt(String resourcePath) {
        try (InputStream in = GroqClient.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalStateException("프롬프트 리소스 없음: " + resourcePath);
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 리소스 읽기 실패: " + resourcePath, e);
        }
    }

    // 도구 및 스키마 정의부

    private List<Tool> buildTools(boolean onlySubmit) {
        if (onlySubmit) {
            return Collections.singletonList(submitTool());
        }
        return Arrays.asList(readFileTool(), listDirTool(), submitTool());
    }

    private Tool readFileTool() {
        return functionTool(TOOL_READ_FILE,
                "코멘트가 가리키는 코드가 호출하는 함수의 정의나 참조하는 설정 파일을 확인할 때 사용. 레포는 PR head 커밋 기준.",
                objectSchema(singletonMap("path", stringProp("조회할 파일의 레포 루트 기준 경로 (예: src/main/Foo.java)")), "path"));
    }

    private Tool listDirTool() {
        return functionTool(TOOL_LIST_DIR,
                "어떤 파일이 어디 있는지 모를 때 디렉터리 구조 탐색.",
                objectSchema(singletonMap("path", stringProp("나열할 디렉터리 경로. 빈 문자열이면 루트.")), "path"));
    }

    private Tool submitTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("comment_summary", stringProp("코멘트가 지적/제안하는 핵심 요약 (한국어)"));
        props.put("current_approach", stringProp("현재 구현 방식 (한국어, 해당 없으면 \"해당 없음\")"));
        props.put("suggested_approach", stringProp("코멘트가 제안하는 방식 (한국어, 없으면 \"해당 없음\")"));

        Map<String, Object> verdict = stringProp("판정 + 한 줄 이유 (한국어)");
        verdict.put("enum", Arrays.asList("제안 채택 권장", "현 구현 유지 권장", "추가 논의 필요"));
        props.put("verdict", verdict);

        props.put("reasoning", stringProp("판정 근거. 조회한 코드/설정에 기반한 트레이드오프 포함 2~5문장 (한국어)"));
        props.put("suggested_reply", stringProp("코멘트에 달 정중하고 구체적인 답변 초안 (한국어)"));

        return functionTool(TOOL_SUBMIT,
                "코드 조사를 마친 뒤 최종 분석 결과를 제출. 이 도구 호출로 분석 종료.",
                objectSchema(props, "comment_summary", "current_approach", "suggested_approach", "verdict", "reasoning", "suggested_reply"));
    }

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> singletonMap(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList(required));
        return schema;
    }

    private static Tool functionTool(String name, String description, Object parameters) {
        return new Tool("function", new FunctionDef(name, description, parameters));
    }

    private Object forcedChoice() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", TOOL_SUBMIT);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", "function");
        choice.put("function", fn);
        return choice;
    }

    private static ChatMessage assistantText(String content) {
        return new ChatMessage(ROLE_ASSISTANT, StringUtils.hasText(content) ? content : "");
    }

    private static ChatMessage assistantWithCalls(ChatMessage am) {
        ChatMessage m = new ChatMessage(ROLE_ASSISTANT, am.getContent());
        m.setToolCalls(am.getToolCalls());
        return m;
    }

    // Groq(OpenAI 호환) 요청/응답 DTO

    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatRequest {
        private final String model;
        private final List<ChatMessage> messages;
        private final double temperature;
        @JsonProperty("max_tokens") private final Integer maxTokens;
        private final List<Tool> tools;
        @JsonProperty("tool_choice") private final Object toolChoice;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatMessage {
        private String role;
        private String content;
        @JsonProperty("tool_calls") private List<ToolCall> toolCalls;
        @JsonProperty("tool_call_id") private String toolCallId;
        private String name;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        static ChatMessage tool(String toolCallId, String content) {
            ChatMessage m = new ChatMessage(ROLE_TOOL, content);
            m.toolCallId = toolCallId;
            return m;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCall {
        private String id;
        private String type;
        private FunctionCall function;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class FunctionCall {
        private String name;
        private String arguments;
    }

    @Getter
    @AllArgsConstructor
    public static class Tool {
        private final String type;
        private final FunctionDef function;
    }

    @Getter
    @AllArgsConstructor
    public static class FunctionDef {
        private final String name;
        private final String description;
        private final Object parameters;
    }

    @Getter
    @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class ChatResponse {
        private List<Choice> choices;
    }

    @Getter
    @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class Choice {
        private ChatMessage message;
        @JsonProperty("finish_reason") private String finishReason;
    }
}
