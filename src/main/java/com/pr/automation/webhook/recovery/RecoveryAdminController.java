package com.pr.automation.webhook.recovery;

import com.pr.automation.webhook.recovery.CoverageReporter.CoverageReport;
import com.pr.automation.webhook.recovery.WebhookRedeliveryRecoverer.RecoverySummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 수동으로 복구/진단을 트리거할 수 있는 내부 관리용 엔드포인트
@RestController
@RequiredArgsConstructor
public class RecoveryAdminController {
    private final WebhookRedeliveryRecoverer recoverer;
    private final CoverageReporter coverageReporter;

    // 부팅 시 자동 실행되는 복구 로직을 수동으로 트리거
    @PostMapping("/internal/recovery/run")
    public RecoverySummary run(@RequestParam(value = "hours", required = false) Integer hours) {
        return recoverer.recoverNow(hours != null ? hours : 0);
    }

    // 발생/처리 코멘트 수 비교 (로그로 "19/20" 형식 출력 + JSON 응답)
    @GetMapping("/internal/recovery/coverage")
    public CoverageReport coverage(@RequestParam(value = "hours", required = false) Integer hours) {
        return coverageReporter.report(hours != null ? hours : 0);
    }
}
