package com.pr.automation.webhook.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pr.automation.config.GithubProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/** 웹훅 복구
 * 받은 delivery가 아니라, 책임을 다 진 delivery의 guid 집합을 영속함
 * 책임을 다 진 경우: ping 수신, 필터링 탈락(봇/타 사용자 PR 등), 분석 성공.
 * 분석 중 크래시는 영속화 하지 않으므로 다음 부팅 시 recoverer가 복구 대상으로 식별됨
 */
@Slf4j
@Component
public class DeliveryStore {
    private static final int MAX_GUIDS = 5_000;
    private static final String DEFAULT_STATE_FILE = "./pr-analyzer-deliveries.json";

    private final Path stateFile; // delivery를 저장할 파일
    private final ObjectMapper objectMapper;
    private final LinkedHashSet<String> received = new LinkedHashSet<>();

    public DeliveryStore(GithubProperties properties, ObjectMapper objectMapper) {
        String path = properties.getWebhookRecovery() != null && StringUtils.hasText(properties.getWebhookRecovery().getStateFile())
                ? properties.getWebhookRecovery().getStateFile()
                : DEFAULT_STATE_FILE;
        this.stateFile = Paths.get(path);
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    synchronized void load() {
        if (!Files.exists(stateFile)) {
            log.info("delivery 상태 파일 없음, 빈 상태로 시작: {}", stateFile.toAbsolutePath());
            return;
        }
        try {
            State state = objectMapper.readValue(stateFile.toFile(), State.class);
            if (state != null && state.getReceivedDeliveryGuids() != null) {
                received.addAll(state.getReceivedDeliveryGuids());
            }
            log.info("delivery 상태 파일 로드 완료: {}건 ({})", received.size(), stateFile.toAbsolutePath());
        } catch (Exception e) {
            log.warn("delivery 상태 파일 로드 실패, 빈 상태로 시작: {}", stateFile, e);
        }
    }

    public synchronized void markReceived(String deliveryGuid) {
        if (!StringUtils.hasText(deliveryGuid)) return;
        if (received.add(deliveryGuid)) {
            while (received.size() > MAX_GUIDS) {
                Iterator<String> it = received.iterator();
                it.next();
                it.remove();
            }
            save();
        }
    }

    public synchronized boolean isReceived(String deliveryGuid) {
        return deliveryGuid != null && received.contains(deliveryGuid);
    }

    synchronized void save() {
        try {
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), new State(new ArrayList<>(received)));
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("delivery 상태 파일 저장 실패: {}", stateFile, e);
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class State {
        private List<String> receivedDeliveryGuids;
    }
}
