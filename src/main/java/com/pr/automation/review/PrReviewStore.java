package com.pr.automation.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.config.PrReviewProperties;
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

// 이미 리뷰한 PR 키(repo#pr)를 기억해 중복 리뷰(웹훅 재전송 포함)를 막습니다.
// CommentStore와 동일한 패턴 - 키 타입만 String
@Slf4j
@Component
public class PrReviewStore {
    private static final int MAX_KEYS = 5_000;

    private final Path stateFile;
    private final ObjectMapper objectMapper;
    private final LinkedHashSet<String> processed = new LinkedHashSet<>();
    // 진행 중인 키. 디스크에 영속하지 않음 — 프로세스 종료 시 소실되어야 데드락이 생기지 않음
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    public PrReviewStore(PrReviewProperties properties, ObjectMapper objectMapper) {
        this.stateFile = Paths.get(properties.getStateFile());
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    synchronized void load() {
        if (!Files.exists(stateFile)) {
            log.info("PR 리뷰 상태 파일 없음, 빈 상태로 시작: {}", stateFile.toAbsolutePath());
            return;
        }
        try {
            State state = objectMapper.readValue(stateFile.toFile(), State.class);
            if (state != null && state.getReviewedPrKeys() != null) {
                processed.addAll(state.getReviewedPrKeys());
            }
            log.info("PR 리뷰 상태 파일 로드 완료: {}건 ({})", processed.size(), stateFile.toAbsolutePath());
        } catch (Exception e) {
            log.warn("PR 리뷰 상태 파일 로드 실패, 빈 상태로 시작: {}", stateFile, e);
        }
    }

    // 이 PR 키를 리뷰할 권리를 점유 — 성공 시 true, 이미 점유됐거나 완료면 false
    public synchronized boolean tryClaim(String prKey) {
        if (processed.contains(prKey)) {
            return false;
        }
        return inProgress.add(prKey);
    }

    public synchronized void markCompleted(String prKey) {
        inProgress.remove(prKey);
        processed.add(prKey);
        while (processed.size() > MAX_KEYS) {
            Iterator<String> it = processed.iterator();
            it.next();
            it.remove();
        }
    }

    // 리뷰 실패 시 점유 해제 — processed에 넣지 않으므로 같은 PR 재시도 가능
    public synchronized void release(String prKey) {
        inProgress.remove(prKey);
    }

    public synchronized void save() {
        try {
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), new State(new ArrayList<>(processed)));
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("PR 리뷰 상태 파일 저장 실패: {}", stateFile, e);
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class State {
        private List<String> reviewedPrKeys;
    }
}
