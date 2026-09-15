package com.pr.automation.analysis.pr.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.pr.dto.PrReviewEvent;
import com.pr.automation.analysis.pr.dto.PrReviewResult;
import com.pr.automation.error.AutomationException;
import com.pr.automation.error.ErrorCode;
import com.pr.automation.github.GithubClient.ChangedFile;
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
import java.util.List;

/** 체크아웃된 저장소 위에서 Claude Code의 네이티브 도구로 PR을 리뷰합니다.
 * 코멘트 분석의 CommentAnalysisAgent와 같은 구조로, 루프와 도구 프로토콜은 CLI가 맡습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrReviewAgent {
    private static final String SYSTEM_PROMPT = loadPrompt("prompts/pr-review-system.md");

    private final LlmChatClient chatClient;
    private final PrReviewPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    // 리뷰
    public PrReviewResult review(PrReviewEvent event, List<ChangedFile> files, Path checkoutDir) {
        String userPrompt = promptBuilder.buildInitialPrompt(event, files);

        LlmResponse response = chatClient.runAgent(SYSTEM_PROMPT, userPrompt, checkoutDir);
        PrReviewResult result = parseResult(response.getMessage() == null ? null : response.getMessage().getContent());

        result.setUsage(response.getUsage());
        log.info("네이티브 PR 리뷰 완료: {} #{} 이슈 {}건, 토큰 {}, 비용 ${}",
                event.getRepoFullName(), event.getPrNumber(),
                result.getMergedFindings() == null ? 0 : result.getMergedFindings().size(),
                response.getUsage().getTotalTokens(), response.getUsage().getCostUsd());
        return result;
    }

    // 응답 전체가 JSON인 것이 정상이지만, 코드 펜스나 앞뒤 설명이 붙어 오는 경우가 있어 JSON 부분만 꺼낸다
    PrReviewResult parseResult(String content) {
        if (!StringUtils.hasText(content)) {
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, "PR 리뷰 빈 응답");
        }
        try {
            PrReviewResult raw = objectMapper.readValue(extractJson(content), PrReviewResult.class);
            // 게시 단계는 mergedFindings를 읽으므로 비어 있으면 findings로 채운다
            if (raw.getFindings() != null && raw.getMergedFindings() == null) {
                raw.setMergedFindings(raw.getFindings());
            }
            return raw;
        } catch (JsonProcessingException e) {
            log.warn("네이티브 PR 리뷰 응답 파싱 실패. 응답: {}", abbreviate(content, 500));
            throw new AutomationException(HttpStatus.BAD_GATEWAY, ErrorCode.AI_RESPONSE_PARSE_ERROR, e);
        }
    }

    private static String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return (start >= 0 && end > start) ? content.substring(start, end + 1) : content.trim();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(없음)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String loadPrompt(String classpathLocation) {
        try (InputStream in = PrReviewAgent.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalStateException("프롬프트 리소스를 찾을 수 없음: " + classpathLocation);
            }
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 로딩 실패: " + classpathLocation, e);
        }
    }
}
