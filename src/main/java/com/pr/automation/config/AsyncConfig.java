package com.pr.automation.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 코멘트 분석을 웹훅 응답 스레드와 분리해 비동기로 처리하기 위한 설정
 * GitHub 웹훅은 ~10초 내 2xx 응답을 기대하므로 분석(LLM 호출)은 별도 풀에서 돌린다
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {
    public static final String ANALYSIS_EXECUTOR = "analysisExecutor";

    @Bean(ANALYSIS_EXECUTOR)
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);

        // 큐 포화는 비정상 상황 - 큐의 한계를 200으로 설정
        executor.setQueueCapacity(2200);
        executor.setThreadNamePrefix("analysis-");
        // 큐도 포화되어 작업이 거부되면 로그 찍고 재시도하게 됨
        executor.setRejectedExecutionHandler((task, ex) ->
                log.error("분석 작업 거부됨 — 큐 포화로 손실. queueSize={}, activeCount={}, poolSize={}",
                ex.getQueue().size(), ex.getActiveCount(), ex.getPoolSize())
        );

        // SIGTERM 시 진행 중 분석이 잘리지 않도록 최대 30초 대기
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return analysisExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("비동기 작업 처리 중 예외 발생: {}", method, ex);
    }
}
