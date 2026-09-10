package com.pr.automation.analysis.comment.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.dto.AnalysisResult;
import com.pr.automation.analysis.comment.dto.CommentContext;
import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import com.pr.automation.github.LocalRepoFileReader;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 체크아웃된 저장소 위에서 Claude Code의 네이티브 도구로 분석을 수행합니다.
 * 프롬프트를 만들고 결과 JSON을 파싱하는 클래스
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentAnalysisAgent {
    private static final String SYSTEM_PROMPT = loadPrompt("prompts/comment-analysis-system.md");
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final LlmChatClient chatClient;
    private final CommentAnalysisPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    // checkoutDir 분석 대상 커밋이 받아진 디렉터리
    public AnalysisResult analyze(CommentContext context, Path checkoutDir) {
        // 코멘트가 달린 파일은 미리 삽입 - 실측상 이것만으로 탐색 턴이 크게 줄어듦을 확인
        String userPrompt = promptBuilder.buildInitial(context, new LocalRepoFileReader(checkoutDir));

        LlmResponse response = chatClient.runAgent(SYSTEM_PROMPT, userPrompt, checkoutDir);
        AnalysisResult result = parseResult(response.getMessage() == null ? null : response.getMessage().getContent());

        result.setUsage(response.getUsage());
        logResult(result, response);
        return result;
    }

    // 응답 전체가 JSON인 것이 정상이지만, 코드 펜스로 감싸 오는 경우가 있어 한 겹 벗겨냄
    AnalysisResult parseResult(String content) {
        if (!StringUtils.hasText(content)) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, "빈 응답");
        }
        try {
            return objectMapper.readValue(extractJson(content), AnalysisResult.class);
        } catch (JsonProcessingException e) {
            log.warn("네이티브 분석 응답 파싱 실패. 응답: {}", abbreviate(content, 500));
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, e);
        }
    }

    private static String extractJson(String content) {
        Matcher m = CODE_FENCE.matcher(content);
        if (m.find()) {
            return m.group(1).trim();
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return (start >= 0 && end > start) ? content.substring(start, end + 1) : content.trim();
    }

    private void logResult(AnalysisResult result, LlmResponse response) {
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
        try (InputStream in = CommentAnalysisAgent.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalStateException("프롬프트 리소스를 찾을 수 없음: " + classpathLocation);
            }
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 로딩 실패: " + classpathLocation, e);
        }
    }
}
