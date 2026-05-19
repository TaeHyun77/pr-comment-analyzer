package com.pr.automation.analysis.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.config.PrAnalyzerProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

// 이미 분석한 코멘트 ID를 기억해 중복 처리를 막음, 로컬 JSON 파일에 영속해 재시작 후에도 유지됨
@Slf4j
@Component
public class CommentStore {
    // 보관할 최대 ID 수, 초과 시 가장 오래된 것부터 버림
    private static final int MAX_IDS = 5_000;

    private final Path stateFile;
    private final ObjectMapper objectMapper;
    private final LinkedHashSet<Long> processed = new LinkedHashSet<>();

    public CommentStore(PrAnalyzerProperties properties, ObjectMapper objectMapper) {
        this.stateFile = Paths.get(properties.getStateFile());
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    synchronized void load() {
        if (!Files.exists(stateFile)) {
            log.info("상태 파일 없음, 빈 상태로 시작: {}", stateFile.toAbsolutePath());
            return;
        }
        try {
            State state = objectMapper.readValue(stateFile.toFile(), State.class);
            if (state != null && state.getProcessedCommentIds() != null) {
                processed.addAll(state.getProcessedCommentIds());
            }
            log.info("상태 파일 로드 완료: {}건 ({})", processed.size(), stateFile.toAbsolutePath());
        } catch (Exception e) {
            log.warn("상태 파일 로드 실패, 빈 상태로 시작: {}", stateFile, e);
        }
    }

    public synchronized boolean isProcessed(long commentId) {
        return processed.contains(commentId);
    }

    public synchronized void markProcessed(long commentId) {
        processed.add(commentId);
        while (processed.size() > MAX_IDS) {
            Iterator<Long> it = processed.iterator();
            it.next();
            it.remove();
        }
    }

    public synchronized void save() {
        try {
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), new State(new ArrayList<>(processed)));
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("상태 파일 저장 실패: {}", stateFile, e);
        }
    }

    // 상태 파일 JSON 매핑용 DTO
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class State {
        private List<Long> processedCommentIds;
    }
}
