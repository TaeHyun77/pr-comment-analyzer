package com.pr.automation.github;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

// RepoFileReader의 구현체
@RequiredArgsConstructor
public class GithubRepoFileReader implements RepoFileReader { // LLM의 깃허브 파일 요청을 GitHub API 호출로 바꿔주는 중간 어댑터
    private final GithubClient githubClient;
    private final String repoFullName;
    private final String ref; // PR head 커밋 SHA로 고정 ( 특정 커밋 시점 기준으로 파일 조회 )

    @Override
    public Optional<String> readFile(String path) {
        return githubClient.fetchFileContent(repoFullName, path, ref)
                .map(GithubClient.FetchedFile::getContent);
    }

    @Override
    public Optional<List<String>> listDirectory(String path) {
        return githubClient.listDirectory(repoFullName, path, ref)
                .map(entries -> entries.stream()
                        .map(e -> e.getType() + "\t" + e.getName() + ("file".equalsIgnoreCase(e.getType()) ? " (" + e.getSize() + "B)" : ""))
                        .collect(Collectors.toList()));
    }
}
