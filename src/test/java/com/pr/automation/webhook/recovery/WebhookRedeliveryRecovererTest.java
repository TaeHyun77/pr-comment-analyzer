package com.pr.automation.webhook.recovery;

import com.pr.automation.config.GithubProperties;
import com.pr.automation.github.GithubDeliveryClient;
import com.pr.automation.github.GithubDeliveryClient.GhDelivery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookRedeliveryRecovererTest {

    private GithubProperties propsWith(java.util.List<String> repos, boolean enabled) {
        return propsWith(repos, enabled, false);
    }

    private GithubProperties propsWith(java.util.List<String> repos, boolean enabled, boolean schedulerEnabled) {
        return new GithubProperties("token", "me", "secret", repos,
                new GithubProperties.WebhookRecovery(enabled, 24, 
                        schedulerEnabled, 1800000L, 1));
    }

    private GhDelivery delivery(long id, String guid, Instant at) {
        GhDelivery d = new GhDelivery();
        d.setId(id);
        d.setGuid(guid);
        d.setDeliveredAt(at);
        d.setEvent("issue_comment");
        d.setAction("created");
        return d;
    }

    @Test
    void 토큰_없으면_API_호출_없이_빈_summary() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(false);

        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/repo"), true), client, store);

        WebhookRedeliveryRecoverer.RecoverySummary s = r.recoverNow(24);
        assertThat(s.getRepos()).isEmpty();
        assertThat(s.getTotalTriggered()).isZero();
        verify(client, never()).findHookId(any());
    }

    @Test
    void repos_비어있으면_API_호출_없음() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);

        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Collections.emptyList(), true), client, store);

        WebhookRedeliveryRecoverer.RecoverySummary s = r.recoverNow(24);
        assertThat(s.getRepos()).isEmpty();
        verify(client, never()).findHookId(any());
    }

    @Test
    void hook_미발견이면_redeliver_호출_안_함() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.findHookId("me/repo")).thenReturn(Optional.empty());

        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/repo"), true), client, store);

        r.recoverNow(24);
        verify(client, never()).listDeliveries(any(), anyLong());
        verify(client, never()).redeliver(any(), anyLong(), anyLong());
    }

    @Test
    void deliveryStore에_없는_것만_redeliver한다() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.findHookId("me/repo")).thenReturn(Optional.of(55L));

        Instant now = Instant.now();
        GhDelivery a = delivery(1L, "g-a", now.minus(1, ChronoUnit.HOURS));
        GhDelivery b = delivery(2L, "g-b", now.minus(2, ChronoUnit.HOURS));
        GhDelivery c = delivery(3L, "g-c", now.minus(3, ChronoUnit.HOURS));
        when(client.listDeliveries("me/repo", 55L)).thenReturn(Optional.of(Arrays.asList(a, b, c)));

        when(store.isReceived("g-a")).thenReturn(true);
        when(store.isReceived("g-b")).thenReturn(false);
        when(store.isReceived("g-c")).thenReturn(true);

        when(client.redeliver(eq("me/repo"), eq(55L), eq(2L))).thenReturn(true);

        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/repo"), true), client, store);

        WebhookRedeliveryRecoverer.RecoverySummary s = r.recoverNow(24);
        assertThat(s.getTotalTriggered()).isEqualTo(1);
        assertThat(s.getTotalSkipped()).isEqualTo(2);
        verify(client, times(1)).redeliver(eq("me/repo"), eq(55L), eq(2L));
        verify(client, never()).redeliver(eq("me/repo"), eq(55L), eq(1L));
        verify(client, never()).redeliver(eq("me/repo"), eq(55L), eq(3L));
    }

    @Test
    void 재전송_상한을_초과한_delivery는_redeliver하지_않는다() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.findHookId("me/repo")).thenReturn(Optional.of(55L));

        GhDelivery poison = delivery(9L, "g-poison", Instant.now().minus(1, ChronoUnit.HOURS));
        when(client.listDeliveries("me/repo", 55L)).thenReturn(Optional.of(Arrays.asList(poison)));
        when(store.isReceived("g-poison")).thenReturn(false);
        when(store.getRedeliverCount("g-poison")).thenReturn(5); // 상한 도달

        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/repo"), true), client, store);

        WebhookRedeliveryRecoverer.RecoverySummary s = r.recoverNow(24);
        assertThat(s.getTotalTriggered()).isZero();
        assertThat(s.getTotalSkipped()).isEqualTo(1);
        verify(client, never()).redeliver(any(), anyLong(), anyLong());
    }

    @Test
    void redeliver_성공_시_재전송_횟수를_기록한다() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.findHookId("me/repo")).thenReturn(Optional.of(55L));

        GhDelivery d = delivery(7L, "g-r", Instant.now().minus(1, ChronoUnit.HOURS));
        when(client.listDeliveries("me/repo", 55L)).thenReturn(Optional.of(Arrays.asList(d)));
        when(store.isReceived("g-r")).thenReturn(false);
        when(store.getRedeliverCount("g-r")).thenReturn(0);
        when(client.redeliver("me/repo", 55L, 7L)).thenReturn(true);

        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/repo"), true), client, store);

        r.recoverNow(24);
        verify(store).recordRedeliverAttempt("g-r");
    }

    @Test
    void lookback_밖_delivery는_break로_skip() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.findHookId("me/repo")).thenReturn(Optional.of(55L));

        Instant now = Instant.now();
        // 응답은 내림차순이므로 first는 lookback 안, second는 lookback 밖
        GhDelivery recent = delivery(1L, "g-recent", now.minus(30, ChronoUnit.MINUTES));
        GhDelivery old = delivery(2L, "g-old", now.minus(50, ChronoUnit.HOURS));
        when(client.listDeliveries("me/repo", 55L)).thenReturn(Optional.of(Arrays.asList(recent, old)));
        when(store.isReceived(anyString())).thenReturn(false);
        when(client.redeliver(any(), anyLong(), anyLong())).thenReturn(true);

        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/repo"), true), client, store);

        WebhookRedeliveryRecoverer.RecoverySummary s = r.recoverNow(24);
        assertThat(s.getTotalTriggered()).isEqualTo(1);
        verify(client).redeliver("me/repo", 55L, 1L);
        verify(client, never()).redeliver(eq("me/repo"), eq(55L), eq(2L));
    }

    @Test
    void 한_repo_예외는_다음_repo로_진행한다() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);

        when(client.findHookId("me/bad")).thenThrow(new RuntimeException("boom"));
        when(client.findHookId("me/good")).thenReturn(Optional.of(77L));

        Instant now = Instant.now();
        GhDelivery d = delivery(10L, "g-x", now.minus(1, ChronoUnit.HOURS));
        when(client.listDeliveries("me/good", 77L)).thenReturn(Optional.of(Arrays.asList(d)));
        when(store.isReceived("g-x")).thenReturn(false);
        when(client.redeliver(eq("me/good"), eq(77L), eq(10L))).thenReturn(true);

        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/bad", "me/good"), true), client, store);

        WebhookRedeliveryRecoverer.RecoverySummary s = r.recoverNow(24);
        assertThat(s.getRepos()).hasSize(2);
        assertThat(s.getTotalTriggered()).isEqualTo(1);
        verify(client).redeliver("me/good", 77L, 10L);
    }

    @Test
    void onStartup_비활성_설정이면_recoverNow_호출되지_않는다() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/repo"), false), client, store);

        r.onStartup();
        verify(client, never()).isEnabled();
        verify(client, never()).findHookId(any());
    }

    @Test
    void scheduledRecover_비활성이면_recoverNow_진입_안_함() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        // 복구 자체는 활성이지만 스케줄러는 비활성
        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/repo"), true, false), client, store);

        r.scheduledRecover();
        verify(client, never()).isEnabled();
        verify(client, never()).findHookId(any());
    }

    @Test
    void scheduledRecover_복구_자체가_비활성이면_진입_안_함() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        // 스케줄러는 활성이지만 상위 복구 자체가 비활성 → 진입 안 함
        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/repo"), false, true), client, store);

        r.scheduledRecover();
        verify(client, never()).isEnabled();
        verify(client, never()).findHookId(any());
    }

    @Test
    void scheduledRecover_활성이면_lookbackHours로_recoverNow_호출됨() {
        GithubDeliveryClient client = mock(GithubDeliveryClient.class);
        DeliveryStore store = mock(DeliveryStore.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.findHookId("me/repo")).thenReturn(Optional.of(55L));
        when(client.listDeliveries("me/repo", 55L))
                .thenReturn(Optional.of(Collections.emptyList()));

        WebhookRedeliveryRecoverer r = new WebhookRedeliveryRecoverer(
                propsWith(Arrays.asList("me/repo"), true, true), client, store);

        r.scheduledRecover();
        // 스케줄러는 schedulerLookbackHours(=1)로 recoverNow 호출 → findHookId까지 진입
        verify(client).findHookId("me/repo");
        verify(client).listDeliveries("me/repo", 55L);
    }
}
