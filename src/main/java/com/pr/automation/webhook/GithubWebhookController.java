package com.pr.automation.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// GitHub 웹훅 수신 엔드포인트, 분석은 비동기로 처리됨
@RestController
@RequiredArgsConstructor
public class GithubWebhookController {
    private final GithubWebhookVerifier verifier;
    private final WebhookEventHandler webhookEventHandler;

    @PostMapping("/webhook/github")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestBody byte[] rawBody) {
        verifier.verify(rawBody, signature);
        webhookEventHandler.handle(event, deliveryId, rawBody);
        return ResponseEntity.ok().build();
    }
}
