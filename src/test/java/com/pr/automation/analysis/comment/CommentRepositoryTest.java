package com.pr.automation.analysis.comment;

import com.pr.automation.analysis.comment.dto.CommentAnalysisResult;
import com.pr.automation.analysis.comment.dto.CommentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// 분석 결과가 실제 DB에 그대로 저장되고, 같은 코멘트가 다시 저장돼도 에러 없이 덮어쓰는지 검증
// 운영과 동일하게 각 save가 독립 트랜잭션이 되도록 테스트 트랜잭션을 끈다
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommentRepositoryTest {

    @Autowired
    private CommentRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void 분석결과와_코멘트정보가_그대로_저장된다() {
        // 긴 근거도 잘리지 않아야 한다 — Lob 컬럼 검증
        String longReasoning = repeat("근거", 1000);
        CommentAnalysisResult result = new CommentAnalysisResult(
                "요약", "현재", "제안", "현 구현 유지 권장 — 변경 비용 대비 이득이 작음", longReasoning, "답변", null);

        repository.save(Comment.from(event(10L), result, Instant.now()));

        Comment saved = repository.findById(10L).orElseThrow(IllegalStateException::new);
        assertThat(saved.getRepoFullName()).isEqualTo("me/repo");
        assertThat(saved.getPrNumber()).isEqualTo(7);
        assertThat(saved.getCommentHtmlUrl()).isEqualTo("https://github.com/me/repo/pull/7#discussion_r10");
        assertThat(saved.getCommentSummary()).isEqualTo("요약");
        assertThat(saved.getCurrentApproach()).isEqualTo("현재");
        assertThat(saved.getSuggestedApproach()).isEqualTo("제안");
        assertThat(saved.getVerdict()).isEqualTo("현 구현 유지 권장 — 변경 비용 대비 이득이 작음");
        assertThat(saved.getReasoning()).isEqualTo(longReasoning);
        assertThat(saved.getSuggestedReply()).isEqualTo("답변");
    }

    @Test
    void 같은_코멘트를_다시_저장하면_마지막_결과로_덮어쓴다() {
        repository.save(Comment.from(event(10L), result("처음 판정"), Instant.now()));
        repository.save(Comment.from(event(10L), result("두번째 판정"), Instant.now()));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(10L).map(Comment::getVerdict)).contains("두번째 판정");
    }

    private static CommentEvent event(long commentId) {
        return CommentEvent.builder()
                .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
                .repoFullName("me/repo")
                .prNumber(7)
                .commentId(commentId)
                .commentHtmlUrl("https://github.com/me/repo/pull/7#discussion_r" + commentId)
                .build();
    }

    private static CommentAnalysisResult result(String verdict) {
        return new CommentAnalysisResult("요약", "현재", "제안", verdict, "근거", "답변", null);
    }

    // Java 8 toolchain이라 String.repeat을 쓸 수 없다
    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
