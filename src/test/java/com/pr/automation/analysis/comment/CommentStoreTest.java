package com.pr.automation.analysis.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.config.PrAnalyzerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommentStoreTest {

    private CommentStore newStore(Path stateFile) {
        CommentStore store = new CommentStore(
                new PrAnalyzerProperties(true, stateFile.toString(), 8, 6, 25000, true, 100),
                new ObjectMapper());
        store.load();
        return store;
    }

    @Test
    void 저장한_ID는_재시작_후에도_처리됨으로_조회된다(@TempDir Path dir) {
        Path stateFile = dir.resolve("state.json");

        CommentStore first = newStore(stateFile);
        assertThat(first.tryClaim(1L)).isTrue();
        first.markCompleted(1L);
        assertThat(first.tryClaim(2L)).isTrue();
        first.markCompleted(2L); // markCompleted가 내부에서 영속
        assertThat(Files.exists(stateFile)).isTrue();

        CommentStore reloaded = newStore(stateFile);
        assertThat(reloaded.tryClaim(1L)).isFalse();
        assertThat(reloaded.tryClaim(2L)).isFalse();
        assertThat(reloaded.tryClaim(3L)).isTrue();
    }

    @Test
    void 상태파일이_없어도_빈_상태로_시작한다(@TempDir Path dir) {
        CommentStore store = newStore(dir.resolve("missing.json"));
        assertThat(store.tryClaim(123L)).isTrue();
    }

    @Test
    void tryClaim은_같은_ID에_대해_한_번만_true이다(@TempDir Path dir) {
        CommentStore store = newStore(dir.resolve("state.json"));

        assertThat(store.tryClaim(10L)).isTrue();
        assertThat(store.tryClaim(10L)).isFalse();
    }

    @Test
    void markCompleted_후_tryClaim은_false이다(@TempDir Path dir) {
        Path stateFile = dir.resolve("state.json");
        CommentStore store = newStore(stateFile);

        assertThat(store.tryClaim(20L)).isTrue();
        store.markCompleted(20L); // markCompleted가 내부에서 영속

        assertThat(store.tryClaim(20L)).isFalse();

        CommentStore reloaded = newStore(stateFile);
        assertThat(reloaded.tryClaim(20L)).isFalse();
    }

    @Test
    void release_후에는_다시_tryClaim_가능하다(@TempDir Path dir) {
        CommentStore store = newStore(dir.resolve("state.json"));

        assertThat(store.tryClaim(30L)).isTrue();
        store.release(30L);
        assertThat(store.tryClaim(30L)).isTrue();
    }

    @Test
    void release한_ID는_파일에_저장되지_않는다(@TempDir Path dir) {
        Path stateFile = dir.resolve("state.json");
        CommentStore store = newStore(stateFile);

        assertThat(store.tryClaim(40L)).isTrue();
        store.release(40L);
        store.save();

        CommentStore reloaded = newStore(stateFile);
        // release 후 save 했으므로 processed에 들어가지 않아 재점유 가능
        assertThat(reloaded.tryClaim(40L)).isTrue();
    }
}
