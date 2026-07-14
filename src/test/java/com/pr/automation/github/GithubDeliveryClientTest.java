package com.pr.automation.github;

import com.pr.automation.config.GithubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GithubDeliveryClientTest {

    private RestTemplate rt;
    private GithubDeliveryClient client;

    @BeforeEach
    void setUp() {
        rt = mock(RestTemplate.class);
        GithubProperties.WebhookRecovery cfg =
                new GithubProperties.WebhookRecovery(true, 24, false, 1800000L, 1);
        client = new GithubDeliveryClient(rt,
                new GithubProperties("ghp", "me", "secret", Collections.emptyList(), cfg));
    }

    private GithubDeliveryClient.GhHookSummary hook(long id, boolean active, List<String> events) {
        GithubDeliveryClient.GhHookSummary h = new GithubDeliveryClient.GhHookSummary();
        h.setId(id);
        h.setActive(active);
        h.setEvents(events);
        return h;
    }

    @Test
    void 토큰_없으면_모든_호출이_비활성() {
        GithubDeliveryClient disabled = new GithubDeliveryClient(rt,
                new GithubProperties("", "me", "secret", Collections.emptyList(),
                        new GithubProperties.WebhookRecovery(true, 24, false, 1800000L, 1)));
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.findHookId("me/repo")).isEmpty();
        assertThat(disabled.listDeliveries("me/repo", 1L)).isEmpty();
        assertThat(disabled.redeliver("me/repo", 1L, 2L)).isFalse();
    }

    @Test
    void findHookId_코멘트_이벤트_구독_active_훅을_반환한다() {
        when(rt.getForObject(eq("/repos/{owner}/{repo}/hooks"), eq(GithubDeliveryClient.GhHookSummary[].class),
                eq("me"), eq("repo")))
                .thenReturn(new GithubDeliveryClient.GhHookSummary[] {
                        hook(99L, true, Arrays.asList("push")),                 // 매칭 안 됨
                        hook(100L, true, Arrays.asList("issue_comment", "push")),
                });

        assertThat(client.findHookId("me/repo")).contains(100L);
    }

    @Test
    void findHookId_매칭_없으면_empty() {
        when(rt.getForObject(eq("/repos/{owner}/{repo}/hooks"), eq(GithubDeliveryClient.GhHookSummary[].class),
                eq("me"), eq("repo")))
                .thenReturn(new GithubDeliveryClient.GhHookSummary[] {
                        hook(99L, true, Arrays.asList("push")),
                });
        assertThat(client.findHookId("me/repo")).isEmpty();
    }

    @Test
    void findHookId_inactive는_제외된다() {
        when(rt.getForObject(eq("/repos/{owner}/{repo}/hooks"), eq(GithubDeliveryClient.GhHookSummary[].class),
                eq("me"), eq("repo")))
                .thenReturn(new GithubDeliveryClient.GhHookSummary[] {
                        hook(99L, false, Arrays.asList("issue_comment")),
                });
        assertThat(client.findHookId("me/repo")).isEmpty();
    }

    @Test
    void findHookId_여러_매칭이면_첫번째_반환() {
        when(rt.getForObject(eq("/repos/{owner}/{repo}/hooks"), eq(GithubDeliveryClient.GhHookSummary[].class),
                eq("me"), eq("repo")))
                .thenReturn(new GithubDeliveryClient.GhHookSummary[] {
                        hook(100L, true, Arrays.asList("issue_comment")),
                        hook(101L, true, Arrays.asList("pull_request_review_comment")),
                });
        assertThat(client.findHookId("me/repo")).contains(100L);
    }

    @Test
    void findHookId_API_오류는_empty() {
        when(rt.getForObject(eq("/repos/{owner}/{repo}/hooks"), eq(GithubDeliveryClient.GhHookSummary[].class),
                eq("me"), eq("repo")))
                .thenThrow(HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));
        assertThat(client.findHookId("me/repo")).isEmpty();
    }

    @Test
    void listDeliveries_정상_매핑() {
        GithubDeliveryClient.GhDelivery d = new GithubDeliveryClient.GhDelivery();
        d.setId(123L);
        d.setGuid("g-1");
        d.setDeliveredAt(Instant.parse("2026-01-01T00:00:00Z"));
        d.setStatus("OK");
        d.setEvent("issue_comment");
        d.setAction("created");

        when(rt.getForObject(eq("/repos/{owner}/{repo}/hooks/{hookId}/deliveries?per_page=100"),
                eq(GithubDeliveryClient.GhDelivery[].class),
                eq("me"), eq("repo"), eq(55L)))
                .thenReturn(new GithubDeliveryClient.GhDelivery[] {d});

        Optional<List<GithubDeliveryClient.GhDelivery>> res = client.listDeliveries("me/repo", 55L);
        assertThat(res).isPresent();
        assertThat(res.get()).hasSize(1);
        assertThat(res.get().get(0).getGuid()).isEqualTo("g-1");
    }

    @Test
    void listDeliveries_null_응답은_빈_리스트() {
        when(rt.getForObject(eq("/repos/{owner}/{repo}/hooks/{hookId}/deliveries?per_page=100"),
                eq(GithubDeliveryClient.GhDelivery[].class),
                eq("me"), eq("repo"), eq(55L)))
                .thenReturn(null);
        Optional<List<GithubDeliveryClient.GhDelivery>> res = client.listDeliveries("me/repo", 55L);
        assertThat(res).isPresent();
        assertThat(res.get()).isEmpty();
    }

    @Test
    void redeliver_2xx_응답이면_true() {
        when(rt.exchange(eq("/repos/{owner}/{repo}/hooks/{hookId}/deliveries/{deliveryId}/attempts"),
                eq(HttpMethod.POST), eq(HttpEntity.EMPTY), eq(Void.class),
                eq("me"), eq("repo"), eq(55L), eq(123L)))
                .thenReturn(ResponseEntity.accepted().build());
        assertThat(client.redeliver("me/repo", 55L, 123L)).isTrue();
    }

    @Test
    void redeliver_예외는_false() {
        when(rt.exchange(eq("/repos/{owner}/{repo}/hooks/{hookId}/deliveries/{deliveryId}/attempts"),
                eq(HttpMethod.POST), eq(HttpEntity.EMPTY), eq(Void.class),
                eq("me"), eq("repo"), eq(55L), eq(123L)))
                .thenThrow(HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", null, null, null));
        assertThat(client.redeliver("me/repo", 55L, 123L)).isFalse();
    }

    @Test
    void 잘못된_레포_형식은_empty_또는_false() {
        assertThat(client.findHookId("invalid")).isEmpty();
        assertThat(client.listDeliveries("invalid", 1L)).isEmpty();
        assertThat(client.redeliver("invalid", 1L, 2L)).isFalse();
        verify(rt, org.mockito.Mockito.never()).getForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Class.class));
    }
}
