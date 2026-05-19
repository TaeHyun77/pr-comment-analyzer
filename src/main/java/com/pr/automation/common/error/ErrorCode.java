package com.pr.automation.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 웹훅
    WEBHOOK_SIGNATURE_INVALID("웹훅 서명이 유효하지 않습니다"),
    WEBHOOK_PAYLOAD_INVALID("웹훅 페이로드를 해석할 수 없습니다"),

    // 외부 API
    GITHUB_API_ERROR("GitHub API 호출에 실패했습니다"),
    AI_API_ERROR("AI 분석 호출에 실패했습니다"),
    AI_RESPONSE_PARSE_ERROR("AI 응답을 파싱할 수 없습니다"),
    SLACK_API_ERROR("Slack 전송에 실패했습니다"),

    // 분석 전제 조건
    REPO_NOT_READABLE("레포 파일을 읽을 수 없어 분석을 진행할 수 없습니다"),

    // 내부
    STATE_IO_ERROR("처리 상태 파일 입출력에 실패했습니다");

    private final String message;
}
