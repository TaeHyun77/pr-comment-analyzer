package com.pr.automation.webhook.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.config.GithubProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryStoreTest {

    private DeliveryStore newStore(Path stateFile) {
        GithubProperties.WebhookRecovery cfg =
                new GithubProperties.WebhookRecovery(true, 24, stateFile.toString(), false, 1800000L, 1);
        GithubProperties props = new GithubProperties(
                "token", "me", "secret", Collections.emptyList(), cfg);
        DeliveryStore store = new DeliveryStore(props, new ObjectMapper());
        store.load();
        return store;
    }

    @Test
    void 저장한_guid는_재시작_후에도_isReceived_true이다(@TempDir Path dir) {
        Path stateFile = dir.resolve("deliveries.json");

        DeliveryStore first = newStore(stateFile);
        first.markReceived("a-1");
        first.markReceived("a-2");
        assertThat(Files.exists(stateFile)).isTrue();

        DeliveryStore reloaded = newStore(stateFile);
        assertThat(reloaded.isReceived("a-1")).isTrue();
        assertThat(reloaded.isReceived("a-2")).isTrue();
        assertThat(reloaded.isReceived("a-3")).isFalse();
    }

    @Test
    void 상태파일이_없어도_빈_상태로_시작한다(@TempDir Path dir) {
        DeliveryStore store = newStore(dir.resolve("missing.json"));
        assertThat(store.isReceived("any")).isFalse();
    }

    @Test
    void null_또는_빈_guid는_무시된다(@TempDir Path dir) {
        DeliveryStore store = newStore(dir.resolve("d.json"));
        store.markReceived(null);
        store.markReceived("");
        assertThat(store.isReceived("")).isFalse();
        assertThat(store.isReceived(null)).isFalse();
    }

    @Test
    void 같은_guid를_여러번_markReceived해도_안전하다(@TempDir Path dir) {
        DeliveryStore store = newStore(dir.resolve("d.json"));
        store.markReceived("x");
        store.markReceived("x");
        assertThat(store.isReceived("x")).isTrue();
    }
}
