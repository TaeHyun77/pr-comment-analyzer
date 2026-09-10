package com.pr.automation.analysis.comment.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.dto.AnalysisResult;
import com.pr.automation.llm.LlmChatClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

// 네이티브 모드는 루프가 CLI 안에 있으므로, 여기서 검증할 것은 응답 텍스트를 결과로 되살리는 부분이다
class CommentAnalysisAgentTest {

    private final CommentAnalysisAgent agent = new CommentAnalysisAgent(
            mock(LlmChatClient.class), mock(CommentAnalysisPromptBuilder.class), new ObjectMapper());

    private static final String JSON =
            "{\"verdict\":\"현 구현 유지 권장 - 근거\",\"reasoning\":\"이유\",\"comment_summary\":\"요약\","
                    + "\"current_approach\":\"현재\",\"suggested_approach\":\"제안\",\"suggested_reply\":\"답변\"}";

    @Test
    void 순수_JSON_응답을_파싱한다() {
        AnalysisResult result = agent.parseResult(JSON);

        assertThat(result.getVerdict()).isEqualTo("현 구현 유지 권장 - 근거");
        assertThat(result.getCommentSummary()).isEqualTo("요약");
        assertThat(result.getSuggestedReply()).isEqualTo("답변");
    }

    @Test
    void 코드_펜스로_감싸_와도_파싱한다() {
        AnalysisResult result = agent.parseResult("```json\n" + JSON + "\n```");

        assertThat(result.getVerdict()).isEqualTo("현 구현 유지 권장 - 근거");
    }

    @Test
    void 앞뒤에_설명이_붙어도_JSON만_추출한다() {
        AnalysisResult result = agent.parseResult("분석했습니다.\n" + JSON + "\n이상입니다.");

        assertThat(result.getReasoning()).isEqualTo("이유");
    }

    @Test
    void 빈_응답이면_예외를_던진다() {
        assertThatThrownBy(() -> agent.parseResult("  ")).hasMessageContaining("빈 응답");
    }

    @Test
    void JSON이_아니면_파싱_예외를_던진다() {
        assertThatThrownBy(() -> agent.parseResult("그냥 텍스트입니다"))
                .isInstanceOf(com.pr.automation.error.AutomationException.class);
    }
}
