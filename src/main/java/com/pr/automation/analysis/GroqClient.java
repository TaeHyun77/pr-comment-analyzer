package com.pr.automation.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.dto.AnalysisResult;
import com.pr.automation.analysis.dto.CommentContext;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.GroqProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groq(OpenAI 호환 Chat Completions API)를 호출해 코멘트를 분석하고 {@link AnalysisResult}로 파싱한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroqClient {

    private static final Pattern CODE_FENCE =
            Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final int PR_BODY_LIMIT = 1_500;
    private static final int CODE_LIMIT = 6_000;

    private final RestTemplate groqRestTemplate;
    private final GroqProperties groqProperties;
    private final ObjectMapper objectMapper;

    public AnalysisResult analyze(CommentContext context) {
        List<ChatMessage> messages = Arrays.asList(
                new ChatMessage("system", systemPrompt()),
                new ChatMessage("user", userPrompt(context)));
        ChatRequest request = new ChatRequest(
                groqProperties.getModel(),
                messages,
                groqProperties.getTemperature(),
                groqProperties.getMaxTokens(),
                groqProperties.isJsonMode() ? new ResponseFormat("json_object") : null);

        ChatResponse response;
        try {
            response = groqRestTemplate.postForObject("/chat/completions", request, ChatResponse.class);
        } catch (RestClientException e) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, e);
        }
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()
                || response.getChoices().get(0).getMessage() == null
                || !StringUtils.hasText(response.getChoices().get(0).getMessage().getContent())) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_API_ERROR, "빈 응답");
        }
        return parse(response.getChoices().get(0).getMessage().getContent());
    }

    AnalysisResult parse(String content) {
        String json = extractJson(content);
        try {
            return objectMapper.readValue(json, AnalysisResult.class);
        } catch (JsonProcessingException e) {
            log.warn("AI 응답 파싱 실패. 응답 일부: {}", abbreviate(content, 500));
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, e);
        }
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

    private String systemPrompt() {
        return "너는 시니어 코드 리뷰어다. 사용자의 GitHub PR에 달린 리뷰 코멘트와 관련 코드/맥락을 보고 다음을 판단해라:\n"
                + "1) 코멘트가 무엇을 지적하거나 제안하는지 핵심 요약\n"
                + "2) 현재 구현 방식이 무엇이고, 코멘트가 제안하는 방식이 무엇인지\n"
                + "3) 두 방식의 트레이드오프를 따져 어느 쪽이 더 나은지 판정 (한쪽이 명백히 낫지 않으면 \"추가 논의 필요\")\n"
                + "4) 사용자가 코멘트에 달 답변 초안 (정중하고 구체적으로)\n"
                + "\n"
                + "반드시 아래 JSON 객체 하나만 출력해라. 설명 문장이나 코드펜스 없이 JSON만:\n"
                + "{\n"
                + "  \"comment_summary\": \"코멘트 핵심 요약\",\n"
                + "  \"current_approach\": \"현재 구현 방식 (해당 없으면 \\\"해당 없음\\\")\",\n"
                + "  \"suggested_approach\": \"코멘트가 제안하는 방식 (없으면 \\\"해당 없음\\\")\",\n"
                + "  \"verdict\": \"제안 채택 권장 | 현 구현 유지 권장 | 추가 논의 필요 중 하나 + 한 줄 이유\",\n"
                + "  \"reasoning\": \"판정 근거 (트레이드오프 포함, 2~5문장)\",\n"
                + "  \"suggested_reply\": \"코멘트에 달 답변 초안\"\n"
                + "}\n"
                + "모든 값은 한국어로 작성해라.\n";
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
            if (c.getLine() != null) {
                sb.append(" (라인 ").append(c.getLine()).append(')');
            }
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
        if (s == null) {
            return "(없음)";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // --- Groq(OpenAI 호환) 요청/응답 DTO ---

    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatRequest {
        private final String model;
        private final List<ChatMessage> messages;
        private final double temperature;
        @JsonProperty("max_tokens")
        private final Integer maxTokens;
        @JsonProperty("response_format")
        private final ResponseFormat responseFormat;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;
        private String content;
    }

    @Getter
    @AllArgsConstructor
    public static class ResponseFormat {
        private final String type;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatResponse {
        private List<Choice> choices;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private ChatMessage message;
    }
}
