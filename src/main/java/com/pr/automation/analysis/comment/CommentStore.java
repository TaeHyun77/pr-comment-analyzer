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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// 이미 분석한 코멘트 ID를 기억해 중복 처리를 막음, 로컬 JSON 파일에 영속해 재시작 후에도 유지됨
@Slf4j
@Component
public class CommentStore {
    // 보관할 최대 ID 수, 초과 시 가장 오래된 것부터 버림
    private static final int MAX_IDS = 5_000;

    private final Path stateFile;
    private final ObjectMapper objectMapper;
    private final LinkedHashSet<Long> processed = new LinkedHashSet<>();
    // 분석이 진행 중인 commentId. 디스크에 영속하지 않음 — 프로세스 종료 시 자동 소실되어야 데드락이 생기지 않음
    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();

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

    // 분석권을 점유, 이미 완료됐거나 다른 스레드가 처리 중이면 false
    public synchronized boolean tryClaim(long commentId) {
        if (processed.contains(commentId)) {
            return false;
        }
        return inProgress.add(commentId);
    }

    // 분석 완료를 기록, inProgress에서 빼고 processed에 추가
    public synchronized void markCompleted(long commentId) {
        inProgress.remove(commentId);
        processed.add(commentId);
        while (processed.size() > MAX_IDS) {
            Iterator<Long> it = processed.iterator();
            it.next();
            it.remove();
        }
    }

    // 분석 실패 시 점유 해제, processed에는 추가하지 않으므로 같은 commentId 재시도 가능
    public synchronized void release(long commentId) {
        inProgress.remove(commentId);
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
