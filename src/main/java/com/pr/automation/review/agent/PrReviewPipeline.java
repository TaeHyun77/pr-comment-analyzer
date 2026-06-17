package com.pr.automation.review.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.llm.GroqChatClient;
import com.pr.automation.analysis.llm.dto.ChatMessage;
import com.pr.automation.analysis.llm.dto.ToolCall;
import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.github.GithubClient.ChangedFile;
import com.pr.automation.review.dto.PrReviewEvent;
import com.pr.automation.review.dto.PrReviewResult;
import com.pr.automation.review.dto.StageReviewResult;
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

/**
 * PR 생성 시 4단계 순차 리뷰를 진행합니다.
 * 단계마다 정확히 1회 LLM을 호출하며, 이전 단계 발견을 다음 단계로 넘깁니다.
 * 1~3단계는 submit_findings, 최종검증 단계는 submit_review를 강제 호출해 구조화 결과를 받습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrReviewPipeline {
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final String STAGE_LANGUAGE = "언어";
    private static final String STAGE_FRAMEWORK = "프레임워크/인프라";
    private static final String STAGE_DOMAIN_SECURITY = "도메인/보안";

    private static final String SYSTEM_LANGUAGE = loadPrompt("prompts/pr-review-language.md");
    private static final String SYSTEM_FRAMEWORK = loadPrompt("prompts/pr-review-framework.md");
    private static final String SYSTEM_DOMAIN_SECURITY = loadPrompt("prompts/pr-review-domain-security.md");
    private static final String SYSTEM_FINAL = loadPrompt("prompts/pr-review-final.md");

    private final GroqChatClient chatClient;
    private final PrReviewPromptBuilder promptBuilder;
    private final PrReviewToolSpecs toolSpecs;
    private final ObjectMapper objectMapper;

    public PrReviewResult run(PrReviewEvent event, List<ChangedFile> files) {
        List<StageReviewResult> stageResults = new ArrayList<>();

        stageResults.add(runStage(STAGE_LANGUAGE, SYSTEM_LANGUAGE, event, files, stageResults));
        stageResults.add(runStage(STAGE_FRAMEWORK, SYSTEM_FRAMEWORK, event, files, stageResults));
        stageResults.add(runStage(STAGE_DOMAIN_SECURITY, SYSTEM_DOMAIN_SECURITY, event, files, stageResults));

        return runFinal(event, stageResults);
    }

    // 1~3단계 공통 실행 — 이전 단계 결과( priorResults )는 호출 시점까지 채워진 누적 목록
    private StageReviewResult runStage(String stageName, String systemPrompt, PrReviewEvent event,
                                       List<ChangedFile> files, List<StageReviewResult> priorResults) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(promptBuilder.buildStagePrompt(event, files, priorResults)));

        ChatMessage response = chatClient.send(messages, toolSpecs.findingsTools(), toolSpecs.findingsChoice());
        StageReviewResult result = parse(response, StageReviewResult.class);
        result.setStageName(stageName);
        log.info("PR 리뷰 단계 완료: {} #{} stage='{}' findings={}",
                event.getRepoFullName(), event.getPrNumber(), stageName,
                result.getFindings() != null ? result.getFindings().size() : 0);
        return result;
    }

    private PrReviewResult runFinal(PrReviewEvent event, List<StageReviewResult> stageResults) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_FINAL));
        messages.add(ChatMessage.user(promptBuilder.buildFinalPrompt(event, stageResults)));

        ChatMessage response = chatClient.send(messages, toolSpecs.reviewTools(), toolSpecs.reviewChoice());
        PrReviewResult result = parse(response, PrReviewResult.class);
        result.setStageSummaries(buildStageSummaries(stageResults));
        log.info("PR 리뷰 최종검증 완료: {} #{} 확정 이슈={}",
                event.getRepoFullName(), event.getPrNumber(),
                result.getMergedFindings() != null ? result.getMergedFindings().size() : 0);
        return result;
    }

    private List<String> buildStageSummaries(List<StageReviewResult> stageResults) {
        List<String> summaries = new ArrayList<>();
        for (StageReviewResult r : stageResults) {
            summaries.add(r.getStageName() + ": " + (StringUtils.hasText(r.getSummary()) ? r.getSummary() : "—"));
        }
        return summaries;
    }

    private <T> T parse(ChatMessage response, Class<T> type) {
        String json = extractArguments(response);
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("PR 리뷰 응답 파싱 실패. 응답: {}", abbreviate(json, 500));
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, e);
        }
    }

    private String extractArguments(ChatMessage response) {
        List<ToolCall> calls = response.getToolCalls();
        if (calls != null && !calls.isEmpty() && calls.get(0).getFunction() != null) {
            String args = calls.get(0).getFunction().getArguments();
            if (StringUtils.hasText(args)) {
                return args;
            }
        }
        if (StringUtils.hasText(response.getContent())) {
            return extractJson(response.getContent());
        }
        throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, "빈 응답(도구 인자/본문 모두 없음)");
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

    private static String abbreviate(String s, int max) {
        if (s == null) return "(없음)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String loadPrompt(String resourcePath) {
        try (InputStream in = PrReviewPipeline.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalStateException("프롬프트 리소스 없음: " + resourcePath);
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 리소스 읽기 실패: " + resourcePath, e);
        }
    }
}
