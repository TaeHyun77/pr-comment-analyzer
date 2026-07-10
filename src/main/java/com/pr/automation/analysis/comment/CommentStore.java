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

    // 이 commentId를 분석할 권리를 점유하며, 성공하면 true / 이미 점유됐거나 처리 완료면 false 반환
    public synchronized boolean tryClaim(long commentId) {
        if (processed.contains(commentId)) {
            return false;
        }
        return inProgress.add(commentId);
    }

    // 분석 완료 시 호출되며, json 파일에 commentId를 기록 ( inProgress에서 빼고 processed에 추가 후 즉시 영속 )
    public synchronized void markCompleted(long commentId) {
        inProgress.remove(commentId);
        processed.add(commentId);
        while (processed.size() > MAX_IDS) {
            Iterator<Long> it = processed.iterator();
            it.next();
            it.remove();
        }
        save();
    }

    // 분석 실패 시 점유 해제, processed에는 추가하지 않으므로 같은 commentId 재시도 가능
    public synchronized void release(long commentId) {
        inProgress.remove(commentId);
    }

    // processed 집합을 임시 파일에 통째로 쓴 뒤 원본 자리로 atomic rename — 쓰기 중 크래시해도 원본이 부분 쓰기 상태로 깨지지 않음
    // 영속 통로는 markCompleted 하나로 한정 — 패키지 밖에서 직접 호출하지 못하게 package-private 유지
    synchronized void save() {
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
