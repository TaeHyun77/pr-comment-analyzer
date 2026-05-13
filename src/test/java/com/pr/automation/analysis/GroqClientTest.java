package com.pr.automation.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.dto.AnalysisResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroqClientTest {

    @Test
    void extractJson_순수_JSON() {
        assertThat(GroqClient.extractJson("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void extractJson_코드펜스_제거() {
        assertThat(GroqClient.extractJson("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(GroqClient.extractJson("결과:\n```\n{\"x\":2}\n```\n끝")).isEqualTo("{\"x\":2}");
    }

    @Test
    void extractJson_앞뒤_텍스트_제거() {
        assertThat(GroqClient.extractJson("여기 결과 {\"k\":\"v\"} 입니다")).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void parse_snake_case_JSON을_AnalysisResult로_변환한다() {
        GroqClient client = new GroqClient(null, null, new ObjectMapper());
        String json = "```json\n"
                + "{\n"
                + "  \"comment_summary\": \"X로 바꾸자는 제안\",\n"
                + "  \"current_approach\": \"B 방식\",\n"
                + "  \"suggested_approach\": \"X 방식\",\n"
                + "  \"verdict\": \"추가 논의 필요 - 성능 측정 후 결정\",\n"
                + "  \"reasoning\": \"두 방식 모두 트레이드오프가 있음\",\n"
                + "  \"suggested_reply\": \"좋은 지적 감사합니다. 측정해보겠습니다.\"\n"
                + "}\n"
                + "```";
        AnalysisResult result = client.parse(json);
        assertThat(result.getCommentSummary()).isEqualTo("X로 바꾸자는 제안");
        assertThat(result.getCurrentApproach()).isEqualTo("B 방식");
        assertThat(result.getSuggestedApproach()).isEqualTo("X 방식");
        assertThat(result.getVerdict()).startsWith("추가 논의 필요");
        assertThat(result.getSuggestedReply()).contains("측정");
    }
}
