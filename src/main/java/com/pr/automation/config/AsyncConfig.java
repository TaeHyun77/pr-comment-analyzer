package com.pr.automation.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    public static final String ANALYSIS_EXECUTOR = "analysisExecutor";

    @Bean(ANALYSIS_EXECUTOR)
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);

        // 큐 포화는 비정상 상황 - 큐의 한계를 100으로 설정
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("analysis-");
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
