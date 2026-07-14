package com.pr.automation.analysis.comment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.agent.AgentPromptBuilder;
import com.pr.automation.analysis.agent.AgentToolSpecs;
import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.github.RepoFileReader;
import com.pr.automation.analysis.llm.GroqChatClient;
import com.pr.automation.analysis.llm.dto.ChatMessage;
import com.pr.automation.analysis.llm.dto.ToolCall;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.PrAnalyzerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 코멘트 분석을 위한 에이전트 루프 - 실질적인 루프 코드
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentAnalysisAgent {
    private static final String SYSTEM_AGENTIC = loadPrompt("prompts/agentic-system.md");
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final GroqChatClient chatClient;
    private final AgentPromptBuilder promptBuilder;
    private final AgentToolSpecs toolSpecs;
    private final ObjectMapper objectMapper;
    private final PrAnalyzerProperties prAnalyzerProperties;

    // 루프 중 변하는 상태를 한 곳에 모음
    private static class AgentState {
        final List<ChatMessage> messages = new ArrayList<>();
        final List<String> exploredFiles = new ArrayList<>();
        int filesReadCount = 0;
        boolean forceNext = false;
    }

    // LLM 분석
    public AnalysisResult run(CommentContext context, RepoFileReader reader) {
        if (reader == null) {
            throw new AutomationException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REPO_NOT_READABLE, "reader 미제공");
        }
        if (prAnalyzerProperties.getMaxToolIterations() <= 0) {
            throw new AutomationException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AI_API_ERROR, "max-tool-iterations가 0 이하로 설정되어 분석 불가");
        }

        // 최대 진행 라운드 수
        int maxRounds = prAnalyzerProperties.getMaxToolIterations();
        AgentState state = new AgentState();

        // 1. 초기 프롬프트 세팅
        state.messages.add(ChatMessage.system(SYSTEM_AGENTIC));
        state.messages.add(ChatMessage.user(promptBuilder.buildInitial(context, reader)));

        // 👾 LLM에게 보낸 프롬프트 내용 debug log
        if (log.isDebugEnabled()) {
            try {
                log.debug("LLM 입력 messages={}", objectMapper.writeValueAsString(state.messages));
            } catch (Exception ignore) { // 디버그용 직렬화 실패는 무시
            }
        }

        // 2. 에이전트 루프 실행
        for (int round = 0; round < maxRounds; round++) {
            boolean isLastRound = (round == maxRounds - 1);
            boolean forceSubmit = state.forceNext || isLastRound;

            // 환각 방어 : 강제 제출 상황이면 엄격한 룰 추가
            if (forceSubmit && isLastRound) {
                state.messages.add(ChatMessage.user("더 이상 파일을 조회할 예산이 없습니다. 반드시 지금까지 확보한 정보만으로 submit_analysis 도구를 호출해 결론을 제출하세요. 알 수 없는 내용은 억지로 지어내지 말고 확인 불가로 명시하세요."));
            }

            ChatMessage responseMessage = chatClient.send(state.messages, toolSpecs.tools(forceSubmit), toolSpecs.choice(forceSubmit));

            // 👾 매 라운드 LLM의 응답 debug log
            if (log.isDebugEnabled()) {
                try {
                    log.debug("LLM 응답 round={} forceSubmit={} message={}", round, forceSubmit, objectMapper.writeValueAsString(responseMessage));
                } catch (Exception ignore) { // 디버그용 직렬화 실패는 무시
                }
            }

            List<ToolCall> calls = responseMessage.getToolCalls();

            // 도구를 사용하지 않은 경우
            if (calls == null || calls.isEmpty()) {
                if (forceSubmit) { // 이미 강제 모드였는데도 텍스트로 답함 -> 텍스트를 파싱해서 결과 반환하도록
                    AnalysisResult fallbackResult = parseFallback(responseMessage.getContent());
                    logResult("폴백 파싱", fallbackResult);
                    return fallbackResult;
                }
                state.messages.add(ChatMessage.assistant(responseMessage.getContent()));
                state.messages.add(ChatMessage.user("일반 텍스트로 답하지 마세요. 반드시 도구를 호출해야 합니다."));
                state.forceNext = true; // 다음 라운드는 강제 모드로 실행
                continue;
            }

            // 도구 사용 및 분석 완료 여부 체크
            state.messages.add(ChatMessage.assistantWithCalls(responseMessage));
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
                case AgentToolSpecs.TOOL_SUBMIT:
                    submittedResult = parseSubmit(args);
                    toolResultString = "분석이 성공적으로 제출되었습니다.";
                    break;

                case AgentToolSpecs.TOOL_READ_FILE:
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

                case AgentToolSpecs.TOOL_LIST_DIR:
                    String dirPath = argPath(args);
                    String targetDir = dirPath == null ? "" : dirPath;
                    int maxDirEntries = prAnalyzerProperties.getMaxDirEntries();
                    toolResultString = reader.listDirectory(targetDir)
                            .map(list -> list.isEmpty() ? "(빈 디렉터리)" : joinLimited(list, maxDirEntries))
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

    // submit_analysis 도구 호출의 JSON 인자를 AnalysisResult 객체로 역직렬
    private AnalysisResult parseSubmit(String arguments) {
        try {
            return objectMapper.readValue(StringUtils.hasText(arguments) ? arguments : "{}", AnalysisResult.class);
        } catch (JsonProcessingException e) {
            log.warn("submit_analysis 인자 파싱 실패. 인자: {}", abbreviate(arguments, 500));
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, e);
        }
    }

    // 도구를 쓰지 않고 자유 텍스트로 답한 경우, 텍스트에서 JSON을 추출하여 AnalysisResult로 파싱
    AnalysisResult parseFallback(String content) {
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

    // 디버그용 : 디버그 로그가 켜져 있을 때 최종 분석 결과를 JSON으로 로깅합니다.
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

    // 도구 호출 인자 JSON에서 path 필드 값을 안전하게 꺼냅니다.
    private String argPath(String arguments) {
        if (!StringUtils.hasText(arguments)) return null;
        try {
            JsonNode node = objectMapper.readTree(arguments).get("path");
            return node != null && !node.isNull() ? node.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // 디렉터리 목록이 너무 길면 앞부분만 보여주고 "총 N개 중 M개만 표시"라고 덧붙입니다.
    private static String joinLimited(List<String> entries, int max) {
        if (max > 0 && entries.size() > max) {
            String head = String.join("\n", entries.subList(0, max));
            return head + "\n…(총 " + entries.size() + "개 중 " + max + "개만 표시)";
        }
        return String.join("\n", entries);
    }

    // 각각 파일 내용/로그용 문자열의 길이를 제한하는 유틸입니다.
    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n…(파일 일부만 표시, 총 " + s.length() + "자)";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(없음)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // 앞뒤 잡텍스트가 섞인 응답에서 순수 JSON 본문만 잘라냅니다.
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

    // 클래스패스 리소스(prompts/agentic-system.md)에서 시스템 프롬프트 텍스트를 정적으로 읽어옵니다 (클래스 로딩 시 1회)
    private static String loadPrompt(String resourcePath) {
        try (InputStream in = CommentAnalysisAgent.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalStateException("프롬프트 리소스 없음: " + resourcePath);
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 리소스 읽기 실패: " + resourcePath, e);
        }
    }
}
