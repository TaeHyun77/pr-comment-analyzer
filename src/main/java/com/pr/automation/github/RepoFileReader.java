package com.pr.automation.github;

import java.util.Optional;

// 초기 프롬프트에 실어 줄 파일을 읽는 경계 인터페이스 — 탐색 자체는 에이전트가 CLI 내장 도구로 직접 한다
public interface RepoFileReader {
    // 파일 내용: 없거나 파일이 아니면 empty 반환
    Optional<String> readFile(String path);
}
