package com.pr.automation.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.analysis.comment.dto.CommentAnalysisResult;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.config.properties.SlackProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Slack 메시지 구성 검증 - 분석 생략은 근거와 원문만 담은 짧은 형식으로 보낸다.
 */
class SlackNotifierPayloadTest {

    private final SlackNotifier notifier =
            new SlackNotifier(mock(RestTemplate.class), new SlackProperties(true, "http://slack.local/hook", 1, 1000));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final CommentEvent REPLY = CommentEvent.builder()
            .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
            .repoFullName("me/repo")
            .prNumber(7)
            .commentId(602L)
            .commentAuthor("reviewer")
            .commentBody("아 그렇네요 감사합니다")
            .commentHtmlUrl("https://github.com/me/repo/pull/7#discussion_r602")
            .filePath("src/Foo.java")
            .line(10)
            .build();

    private String render(CommentAnalysisResult result) throws Exception {
        return objectMapper.writeValueAsString(notifier.buildPayload(REPLY, result));
    }

    @Test
    void 분석_생략은_근거와_원문만_담은_짧은_형식으로_보낸다() throws Exception {
        CommentAnalysisResult skipped = new CommentAnalysisResult("", "", "",
                "분석 생략 - 해명에 동의하며 마무리하는 답글",
                "@myname(PR 작성자)의 'X 때문에 맞다'는 해명에 동의하며 대화를 마무리하는 답글입니다.", "", null);

        String json = render(skipped);

        assertThat(json).contains("분석 생략", "@reviewer", "아 그렇네요 감사합니다", "'X 때문에 맞다'는 해명에 동의", "src/Foo.java:10");
        // 판단할 주장이 없으므로 분석 결과 항목과 답변 초안은 싣지 않는다
        assertThat(json).doesNotContain("현재 방식", "제안 방식", "제안 답변");
    }

    @Test
    void 일반_판정은_기존의_전체_분석_형식으로_보낸다() throws Exception {
        CommentAnalysisResult analyzed = new CommentAnalysisResult("요약", "현재", "제안", "현 구현 유지 권장 - 이유", "근거", "답변", null);

        String json = render(analyzed);

        assertThat(json).contains("현재 방식", "제안 방식", "제안 답변").doesNotContain("분석 생략");
    }

    @Test
    void 분석_생략_판정은_verdict의_시작으로만_판별한다() {
        assertThat(new CommentAnalysisResult(null, null, null, " 분석 생략 - 감사 인사", null, null, null).isSkipped()).isTrue();
        // 근거 문장 안에 '분석 생략'이 들어가도 일반 판정을 생략으로 오인하지 않는다
        assertThat(new CommentAnalysisResult(null, null, null, "추가 논의 필요 - 분석 생략 여부와 무관", null, null, null).isSkipped()).isFalse();
        assertThat(new CommentAnalysisResult(null, null, null, null, null, null, null).isSkipped()).isFalse();
    }
}
