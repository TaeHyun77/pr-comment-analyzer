package com.pr.automation.github;

import java.util.List;
import java.util.Optional;

// LLM 에이전트가 레포 파일을 자율 조회할 때 사용하는 경계 인터페이스
public interface RepoFileReader {
    // 파일 내용: 없거나 파일이 아니면 empty 반환
    Optional<String> readFile(String path);

    // 디렉터리 항목 목록 반환, 없으면 empty.
    Optional<List<String>> listDirectory(String path);
}
