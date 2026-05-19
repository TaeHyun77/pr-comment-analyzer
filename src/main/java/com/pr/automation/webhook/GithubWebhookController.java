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

    // 저장소 웹훅에 구독된 모든 이벤트를 받음
    @PostMapping("/webhook/github")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event, // GitHub가 보낸 이벤트 종류(issue_comment, pull_request_review_comment, push 등)
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature, // 요청이 진짜 GitHub에서 온 건지 검증용
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId, // 이 전송 1건의 고유 ID -> 중복 전송 추적용
            @RequestBody byte[] rawBody // 실제 페이로드 값
    ) {
        verifier.verify(rawBody, signature);
        webhookEventHandler.handle(event, deliveryId, rawBody);
        return ResponseEntity.ok().build();
    }
}
