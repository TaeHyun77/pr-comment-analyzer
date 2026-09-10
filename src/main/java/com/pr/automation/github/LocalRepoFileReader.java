package com.pr.automation.github;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 체크아웃된 로컬 디렉터리에서 파일을 읽는 구현체입니다.
 * 네이티브 도구 모드에서는 에이전트가 직접 파일을 뒤지므로, 이 구현체는
 * 초기 프롬프트에 코멘트가 달린 파일을 미리 실어주는 용도로만 쓰입니다.
 */
@Slf4j
@RequiredArgsConstructor
public class LocalRepoFileReader implements RepoFileReader {

    private final Path root;

    @Override
    public Optional<String> readFile(String path) {
        Path target = resolve(path);
        if (target == null || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("로컬 파일 읽기 실패: {}", path, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<String>> listDirectory(String path) {
        Path target = resolve(path == null ? "" : path);
        if (target == null || !Files.isDirectory(target)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(target)) {
            return Optional.of(entries
                    .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .sorted()
                    .collect(Collectors.toList()));
        } catch (IOException e) {
            log.warn("로컬 디렉터리 조회 실패: {}", path, e);
            return Optional.empty();
        }
    }

    // 체크아웃 루트 밖으로 벗어나는 경로는 거부한다 - ../ 같은 입력으로 호스트 파일을 읽지 못하게 함
    private Path resolve(String path) {
        Path candidate = root.resolve(path).normalize();
        return candidate.startsWith(root.normalize()) ? candidate : null;
    }
}
