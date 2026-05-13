package com.pr.automation.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.config.PrAnalyzerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedCommentStoreTest {

    private ProcessedCommentStore newStore(Path stateFile) {
        ProcessedCommentStore store = new ProcessedCommentStore(
                new PrAnalyzerProperties(true, stateFile.toString(), 40),
                new ObjectMapper());
        store.load();
        return store;
    }

    @Test
    void 저장한_ID는_재시작_후에도_처리됨으로_조회된다(@TempDir Path dir) {
        Path stateFile = dir.resolve("state.json");

        ProcessedCommentStore first = newStore(stateFile);
        assertThat(first.isProcessed(1L)).isFalse();
        first.markProcessed(1L);
        first.markProcessed(2L);
        first.save();
        assertThat(Files.exists(stateFile)).isTrue();

        ProcessedCommentStore reloaded = newStore(stateFile);
        assertThat(reloaded.isProcessed(1L)).isTrue();
        assertThat(reloaded.isProcessed(2L)).isTrue();
        assertThat(reloaded.isProcessed(3L)).isFalse();
    }

    @Test
    void 상태파일이_없어도_빈_상태로_시작한다(@TempDir Path dir) {
        ProcessedCommentStore store = newStore(dir.resolve("missing.json"));
        assertThat(store.isProcessed(123L)).isFalse();
    }
}
