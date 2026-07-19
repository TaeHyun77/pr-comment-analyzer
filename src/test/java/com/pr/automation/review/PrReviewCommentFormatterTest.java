package com.pr.automation.review;

import com.pr.automation.review.dto.PrReviewResult;
import com.pr.automation.review.dto.ReviewFinding;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class PrReviewCommentFormatterTest {

    private final PrReviewCommentFormatter formatter = new PrReviewCommentFormatter();

    @Test
    void 이슈가_있으면_심각도순으로_정렬해_렌더링한다() {
        PrReviewResult result = PrReviewResult.builder()
                .overallSummary("요약")
                .reviewerFocusNotes("본질적 판단 포인트")
                .stageSummaries(Arrays.asList("언어: ok", "프레임워크/인프라: ok", "도메인/보안: ok"))
                .mergedFindings(Arrays.asList(
                        ReviewFinding.builder().file("A.java").line(10).severity("낮음")
                                .category("style").title("사소").detail("d").suggestion("s").build(),
                        ReviewFinding.builder().file("B.java").line(20).severity("높음")
                                .category("null-safety").title("NPE").detail("d").suggestion("s").build()))
                .build();

        String md = formatter.format(result);

        assertThat(md).contains("## 자동 PR 리뷰 (4단계)");
        assertThat(md).contains("확정 이슈 (2건)");
        assertThat(md).contains("본질적 판단 포인트");
        assertThat(md).contains("단계별 총평");
        // 높음(B.java)이 낮음(A.java)보다 먼저 나와야 함
        assertThat(md.indexOf("B.java")).isLessThan(md.indexOf("A.java"));
    }

    @Test
    void 출력_맨_앞에_식별_마커를_포함한다() {
        // 재진입 시 이 마커로 게시 여부를 판별하므로, 마커가 빠지면 중복 게시 방지가 무력화됨
        String md = formatter.format(PrReviewResult.builder().overallSummary("요약").build());

        assertThat(md).startsWith(PrReviewCommentFormatter.MARKER);
    }

    @Test
    void 이슈가_없으면_없음_메시지를_렌더링한다() {
        PrReviewResult result = PrReviewResult.builder()
                .overallSummary("요약")
                .reviewerFocusNotes("포인트")
                .mergedFindings(Collections.emptyList())
                .build();

        String md = formatter.format(result);

        assertThat(md).contains("발견된 기계적 이슈가 없습니다");
    }
}
