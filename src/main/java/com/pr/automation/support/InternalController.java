package com.pr.automation.support;

import com.pr.automation.analysis.comment.CommentAnalysisService;
import com.pr.automation.analysis.dto.CommentEvent;
import com.pr.automation.webhook.WebhookEventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

// 수동 테스트용 엔드포인트
@RestController
@RequiredArgsConstructor
public class InternalController {
    private final WebhookEventHandler webhookEventHandler;
    private final CommentAnalysisService commentAnalysisService;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Collections.singletonMap("status", "UP");
    }

    @PostMapping("/internal/analyze")
    public ResponseEntity<?> analyze(
            @RequestHeader(value = "X-GitHub-Event", required = false, defaultValue = "pull_request_review_comment") String event,
            @RequestBody byte[] rawBody
    ) {
        Optional<CommentEvent> extracted = webhookEventHandler.extract(event, rawBody);
        if (!extracted.isPresent()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(commentAnalysisService.analyze(extracted.get()));
    }
}
