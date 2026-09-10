package com.pr.automation.analysis.comment;

import com.pr.automation.analysis.comment.dto.CommentEvent;
import com.pr.automation.config.properties.CommentAnalyzerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

// 스케줄러는 대상 선별 결과를 그대로 다시 던지기만 하므로, 선별 조건이 아니라 위임과 예외 처리를 검증한다
class CommentRetrySchedulerTest {

    private CommentStore store;
    private CommentAnalysisService analysisService;

    private CommentRetryScheduler scheduler(boolean enabled) {
        CommentAnalyzerProperties properties =
                new CommentAnalyzerProperties(true, 25000, enabled, 3, 3, 20);
        return new CommentRetryScheduler(store, analysisService, properties);
    }

    @BeforeEach
    void setUp() {
        store = mock(CommentStore.class);
        analysisService = mock(CommentAnalysisService.class);
    }

    @Test
    void 기동시_잔존_점유를_강등한다() {
        when(store.demoteStaleInProgress()).thenReturn(2);

        scheduler(true).demoteStaleClaimsOnStartup();

        verify(store).demoteStaleInProgress();
    }

    @Test
    void 기동_강등이_실패해도_예외가_전파되지_않는다() {
        doThrow(new RuntimeException("db down")).when(store).demoteStaleInProgress();

        assertThatCode(() -> scheduler(true).demoteStaleClaimsOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void 재시도_대상을_모두_analyzeAsync에_던진다() {
        when(store.findRetryTargets(20)).thenReturn(Arrays.asList(event(1L), event(2L)));

        scheduler(true).retryPending();

        verify(analysisService, times(2)).analyzeAsync(any(CommentEvent.class));
    }

    @Test
    void 재시도_대상이_없으면_아무것도_던지지_않는다() {
        when(store.findRetryTargets(anyInt())).thenReturn(Collections.emptyList());

        scheduler(true).retryPending();

        verify(analysisService, never()).analyzeAsync(any(CommentEvent.class));
    }

    @Test
    void 조회가_실패해도_예외가_전파되지_않는다() {
        // 한 사이클 실패로 스케줄 자체가 멈추면 안 된다
        when(store.findRetryTargets(anyInt())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> scheduler(true).retryPending()).doesNotThrowAnyException();
    }

    @Test
    void kill_switch가_꺼져_있으면_강등도_재시도도_하지_않는다() {
        CommentRetryScheduler disabled = scheduler(false);

        disabled.demoteStaleClaimsOnStartup();
        disabled.retryPending();

        verify(store, never()).demoteStaleInProgress();
        verify(store, never()).findRetryTargets(anyInt());
        verify(analysisService, never()).analyzeAsync(any(CommentEvent.class));
    }

    private static CommentEvent event(long commentId) {
        return CommentEvent.builder()
                .commentId(commentId)
                .repoFullName("me/repo")
                .eventType(CommentEvent.TYPE_REVIEW_COMMENT)
                .prNumber(1)
                .build();
    }
}
