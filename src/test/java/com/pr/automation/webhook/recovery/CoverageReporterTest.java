package com.pr.automation.webhook.recovery;

import com.pr.automation.config.GithubProperties;
import com.pr.automation.github.GithubDeliveryClient;
import com.pr.automation.github.GithubDeliveryClient.GhDelivery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoverageReporterTest {

    private GithubProperties propsWith(List<String> repos) {
        return new GithubProperties("token", "me", "secret", repos,
                new GithubProperties.WebhookRecovery(true, 24, false, 1800000L, 1));
    }

    private GhDelivery delivery(String guid, String event, String action, Instant at) {
        GhDelivery d = new GhDelivery();
        d.setId(System.nanoTime());
        d.setGuid(guid);
        d.setEvent(event);
        d.setAction(action);
        d.setDeliveredAt(at);
        d.setStatus("OK");
        return d;
    }

    @Test
    void 토큰_없으면_빈_보고서() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(false);

        CoverageReporter reporter = new CoverageReporter(
                propsWith(Arrays.asList("me/repo")), client, store);

        CoverageReporter.CoverageReport rep = reporter.report(24);
        assertThat(rep.getRepos()).isEmpty();
        assertThat(rep.getTotalComments()).isZero();
    }

    @Test
    void repos_미설정이면_빈_보고서() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);

        CoverageReporter reporter = new CoverageReporter(
                propsWith(Collections.emptyList()), client, store);

        CoverageReporter.CoverageReport rep = reporter.report(24);
        assertThat(rep.getRepos()).isEmpty();
    }

    @Test
    void PR_코멘트_이벤트만_분모로_카운트한다() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.findHookId("me/repo")).thenReturn(Optional.of(55L));

        Instant now = Instant.now();
        // PR 코멘트 4개 + 무관 이벤트 2개 + edited action 1개
        when(client.listDeliveries("me/repo", 55L)).thenReturn(Optional.of(Arrays.asList(
                delivery("g-1", "issue_comment", "created", now.minus(1, ChronoUnit.HOURS)),
                delivery("g-2", "pull_request_review_comment", "created", now.minus(2, ChronoUnit.HOURS)),
                delivery("g-3", "issue_comment", "created", now.minus(3, ChronoUnit.HOURS)),
                delivery("g-4", "pull_request_review_comment", "created", now.minus(4, ChronoUnit.HOURS)),
                delivery("g-5", "issue_comment", "edited", now.minus(5, ChronoUnit.HOURS)),    // action != created
                delivery("g-6", "push", "created", now.minus(6, ChronoUnit.HOURS)),            // event 무관
                delivery("g-7", "ping", null, now.minus(7, ChronoUnit.HOURS))                  // ping
        )));

        when(store.isReceived("g-1")).thenReturn(true);
        when(store.isReceived("g-2")).thenReturn(true);
        when(store.isReceived("g-3")).thenReturn(true);
        when(store.isReceived("g-4")).thenReturn(false); // 누락 1건

        CoverageReporter reporter = new CoverageReporter(
                propsWith(Arrays.asList("me/repo")), client, store);
        CoverageReporter.CoverageReport rep = reporter.report(24);

        assertThat(rep.getTotalComments()).isEqualTo(4);
        assertThat(rep.getProcessedComments()).isEqualTo(3);
        assertThat(rep.getRepos()).hasSize(1);
        assertThat(rep.getRepos().get(0).getMissingGuids()).containsExactly("g-4");
    }

    @Test
    void lookback_밖_delivery는_break() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.findHookId("me/repo")).thenReturn(Optional.of(55L));

        Instant now = Instant.now();
        when(client.listDeliveries("me/repo", 55L)).thenReturn(Optional.of(Arrays.asList(
                delivery("g-recent", "issue_comment", "created", now.minus(1, ChronoUnit.HOURS)),
                delivery("g-old", "issue_comment", "created", now.minus(50, ChronoUnit.HOURS))
        )));
        when(store.isReceived("g-recent")).thenReturn(true);

        CoverageReporter reporter = new CoverageReporter(
                propsWith(Arrays.asList("me/repo")), client, store);
        CoverageReporter.CoverageReport rep = reporter.report(24);

        assertThat(rep.getTotalComments()).isEqualTo(1);
        assertThat(rep.getProcessedComments()).isEqualTo(1);
    }

    @Test
    void 여러_repo면_합계_누적() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.findHookId("me/a")).thenReturn(Optional.of(10L));
        when(client.findHookId("me/b")).thenReturn(Optional.of(20L));

        Instant now = Instant.now();
        when(client.listDeliveries("me/a", 10L)).thenReturn(Optional.of(Arrays.asList(
                delivery("a-1", "issue_comment", "created", now.minus(1, ChronoUnit.HOURS)),
                delivery("a-2", "issue_comment", "created", now.minus(2, ChronoUnit.HOURS))
        )));
        when(client.listDeliveries("me/b", 20L)).thenReturn(Optional.of(Arrays.asList(
                delivery("b-1", "pull_request_review_comment", "created", now.minus(1, ChronoUnit.HOURS))
        )));
        when(store.isReceived("a-1")).thenReturn(true);
        when(store.isReceived("b-1")).thenReturn(true);

        CoverageReporter reporter = new CoverageReporter(
                propsWith(Arrays.asList("me/a", "me/b")), client, store);
        CoverageReporter.CoverageReport rep = reporter.report(24);

        assertThat(rep.getTotalComments()).isEqualTo(3);
        assertThat(rep.getProcessedComments()).isEqualTo(2);
        assertThat(rep.getRepos()).hasSize(2);
    }
}
