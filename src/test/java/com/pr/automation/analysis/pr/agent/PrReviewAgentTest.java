package com.pr.automation.analysis.pr.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.pr.dto.PrReviewResult;
import com.pr.automation.llm.LlmChatClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// 응답 텍스트를 리뷰 결과로 되살리는 부분만 검증한다 — 리뷰 제안에는 코드 예시가 들어가기 쉬워 펜스 처리가 깨지기 쉽다
class PrReviewAgentTest {

    private final PrReviewAgent agent = new PrReviewAgent(
            mock(LlmChatClient.class), mock(PrReviewPromptBuilder.class), new ObjectMapper());

    // 모델이 JSON 문자열에 넣은 줄바꿈은 \n으로 이스케이프되어 오지만 백틱은 그대로 온다
    private static final String JSON_WITH_CODE_BLOCK =
            "{\"overall_summary\":\"요약\",\"reviewer_focus_notes\":\"없음\",\"findings\":[{\"file\":\"A.java\",\"line\":3,"
                    + "\"severity\":\"중\",\"category\":\"언어\",\"title\":\"NPE\",\"detail\":\"d\","
                    + "\"suggestion\":\"```java\\nOptional.ofNullable(x)\\n```\"}]}";

    @Test
    void 값_안에_코드_블록이_있어도_파싱한다() {
        PrReviewResult result = agent.parseResult(JSON_WITH_CODE_BLOCK);

        assertThat(result.getMergedFindings()).hasSize(1);
        assertThat(result.getMergedFindings().get(0).getSuggestion()).isEqualTo("```java\nOptional.ofNullable(x)\n```");
    }

    @Test
    void 코드_펜스로_감싼_응답의_값_안에_코드_블록이_있어도_파싱한다() {
        PrReviewResult result = agent.parseResult("```json\n" + JSON_WITH_CODE_BLOCK + "\n```");

        assertThat(result.getMergedFindings().get(0).getSuggestion()).isEqualTo("```java\nOptional.ofNullable(x)\n```");
    }

    @Test
    void 코드_펜스로_감싸_와도_파싱한다() {
        PrReviewResult result = agent.parseResult("```json\n{\"overall_summary\":\"요약\",\"findings\":[]}\n```");

        assertThat(result.getOverallSummary()).isEqualTo("요약");
    }

    @Test
    void 앞뒤에_설명이_붙어도_JSON만_추출한다() {
        PrReviewResult result = agent.parseResult("리뷰했습니다.\n" + JSON_WITH_CODE_BLOCK + "\n이상입니다.");

        assertThat(result.getOverallSummary()).isEqualTo("요약");
    }
}
