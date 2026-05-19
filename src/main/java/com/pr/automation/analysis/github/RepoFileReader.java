package com.pr.automation.analysis.github;

import java.util.List;
import java.util.Optional;

/**
 * LLM 에이전트가 레포 파일을 자율 조회할 때 쓰는 경계 인터페이스
 * 레포/커밋(ref)은 구현체가 고정하며, LLM은 경로만 지정한다(읽기 전용·단일 레포 스코프)
 * 조회 실패는 예외가 아닌 빈 값으로 표현해 에이전트가 경로를 바꿔 재시도할 수 있게 한다.
 */
public interface RepoFileReader {
    // 파일 내용 : 없거나 파일이 아니면 empty 반환
    Optional<String> readFile(String path);

    // 디렉터리 항목 목록 (각 항목은 "type\tname (size)" 형태의 사람이 읽을 문자열). 없으면 empty.
    Optional<List<String>> listDirectory(String path);
}
