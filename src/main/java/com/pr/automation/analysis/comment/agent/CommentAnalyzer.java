package com.pr.automation.analysis.comment.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.dto.CommentAnalysisResult;
import com.pr.automation.analysis.comment.dto.CommentContext;
import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import com.pr.automation.llm.LlmChatClient;
import com.pr.automation.llm.dto.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Component
public class CommentAnalyzer { // 분석 맥락을 프롬프트로 바꿔 claude CLI에 넘기고, 응답 JSON을 코멘트별 분석 결과로 되돌리는 클래스
    private static final String SYSTEM_PROMPT = loadPrompt("prompts/comment-analysis-system.md");
    private static final String BATCH_SYSTEM_PROMPT = SYSTEM_PROMPT + loadPrompt("prompts/comment-analysis-batch.md");

    private final LlmChatClient chatClient;
    private final CommentAnalysisPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    // 코멘트 1건의 프롬프트를 만들어 claude 실행을 요청하고, 응답을 분석 결과로 파싱하여 사용량과 함께 반환
    public CommentAnalysisResult analyze(CommentContext context, Path checkoutDir) {
        String userPrompt = promptBuilder.buildInitial(context);

        LlmResponse response = chatClient.runAgent(SYSTEM_PROMPT, userPrompt, checkoutDir);
        CommentAnalysisResult result = parseResult(response.getMessage() == null ? null : response.getMessage().getContent());

        result.setUsage(response.getUsage());
        logResult(result, response);
        return result;
    }

    // 같은 리뷰로 제출된 코멘트와 총평 여러 건을 claude 1회로 분석하도록 요청하고, 응답을 코멘트 id별 결과로 파싱하여 반환
    public Map<Long, CommentAnalysisResult> analyzeBatch(List<CommentContext> contexts, Path checkoutDir) {
        LlmResponse response = chatClient.runAgent(BATCH_SYSTEM_PROMPT, promptBuilder.buildBatch(contexts), checkoutDir);
        Map<Long, CommentAnalysisResult> results = parseBatchResult(response.getMessage() == null ? null : response.getMessage().getContent());

        log.info("네이티브 묶음 분석 완료 (코멘트 {}건 중 결과 {}건). 토큰 {} (입력 {}, 캐시쓰기 {}, 캐시읽기 {}, 출력 {}), 비용 ${}",
                contexts.size(), results.size(),
                response.getUsage().getTotalTokens(),
                response.getUsage().getInputTokens(),
                response.getUsage().getCacheCreationTokens(),
                response.getUsage().getCacheReadTokens(),
                response.getUsage().getOutputTokens(),
                response.getUsage().getCostUsd());
        return results;
    }

    CommentAnalysisResult parseResult(String content) {
        if (!StringUtils.hasText(content)) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, "빈 응답");
        }
        try {
            return objectMapper.readValue(extractJson(content), CommentAnalysisResult.class);
        } catch (JsonProcessingException e) {
            log.warn("네이티브 분석 응답 파싱 실패. 응답: {}", abbreviate(content, 500));
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, e);
        }
    }

    Map<Long, CommentAnalysisResult> parseBatchResult(String content) {
        if (!StringUtils.hasText(content)) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, "빈 응답");
        }
        try {
            JsonNode items = objectMapper.readTree(extractJson(content)).path("results");
            if (!items.isArray()) {
                throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, "묶음 응답에 results 배열이 없음");
            }
            Map<Long, CommentAnalysisResult> results = new HashMap<>();
            for (JsonNode item : items) {
                long commentId = item.path("comment_id").asLong(0L);
                if (commentId != 0L) {
                    results.put(commentId, objectMapper.treeToValue(item, CommentAnalysisResult.class));
                }
            }
            return results;
        } catch (JsonProcessingException e) {
            log.warn("네이티브 묶음 분석 응답 파싱 실패. 응답: {}", abbreviate(content, 500));
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, e);
        }
    }

    private static String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return (start >= 0 && end > start) ? content.substring(start, end + 1) : content.trim();
    }

    private void logResult(CommentAnalysisResult result, LlmResponse response) {
        log.info("네이티브 분석 완료. 토큰 {} (입력 {}, 캐시쓰기 {}, 캐시읽기 {}, 출력 {}), 비용 ${}",
                response.getUsage().getTotalTokens(),
                response.getUsage().getInputTokens(),
                response.getUsage().getCacheCreationTokens(),
                response.getUsage().getCacheReadTokens(),
                response.getUsage().getOutputTokens(),
                response.getUsage().getCostUsd());
        if (log.isDebugEnabled()) {
            try {
                log.debug("분석 결과(native)={}", objectMapper.writeValueAsString(result));
            } catch (JsonProcessingException ignored) {
                // 디버그 로깅 실패는 분석에 영향을 주지 않는다
            }
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(없음)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String loadPrompt(String classpathLocation) {
        try (InputStream in = CommentAnalyzer.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalStateException("프롬프트 리소스를 찾을 수 없음: " + classpathLocation);
            }
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 로딩 실패: " + classpathLocation, e);
        }
    }
}
