package com.pr.automation.common.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class CustomExceptionHandler {
    @ExceptionHandler(AutomationException.class)
    public ResponseEntity<ErrorDto> handle(AutomationException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("처리 중 예외: {}", e.getMessage(), e);
        } else {
            log.warn("처리 중 예외: {}", e.getMessage());
        }
        return ResponseEntity.status(e.getStatus()).body(ErrorDto.from(e));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> handle(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorDto.of("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다"));
    }
}
