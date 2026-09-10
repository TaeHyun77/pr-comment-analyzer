package com.pr.automation.config.properties;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

@Getter
@AllArgsConstructor
@ConstructorBinding
@ConfigurationProperties("comment-analyzer")
public class CommentAnalyzerProperties {

    private final boolean includeOwnComments;

    private final int maxFileChars; // 파일 1개를 LLM에 전달할 때의 문자 상한 (토큰 보호)

    private final boolean enabled; // 코멘트 분석 전역 on/off (kill-switch). false면 분석을 트리거하지 않음

    // ANALYZED(통지 대기) 행의 재점유 유예(분). 통지 중인 워커를 뺏지 않도록 Slack 전송 최악 시간(재시도 × read timeout)을 넘겨 잡는다
    // 분석 단계는 유예를 두지 않는다 — IN_PROGRESS는 재점유 대상이 아니며, 잔존 행은 기동 시 FAILED로 강등해 복구한다
    private final int notifyRetryDelayMinutes;

    // 자동 재시도를 포기하는 점유 성공 누적 횟수. 최초 점유가 1이므로 3이면 재시도는 2번이다
    // 재시도할 때마다 실패 알림이 나가므로 높게 잡으면 알림이 그만큼 반복된다
    private final int retryMaxAttempts;

    // 재시도 한 사이클에 던지는 최대 건수. 분석 실행기의 큐(AsyncConfig)를 복구가 채워
    // 정상 웹훅이 거부되지 않도록 제한한다
    private final int retryBatchSize;

}
