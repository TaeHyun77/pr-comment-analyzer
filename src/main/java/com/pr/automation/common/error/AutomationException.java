package com.pr.automation.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AutomationException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode errorCode;
    private final String detail;

    public AutomationException(HttpStatus status, ErrorCode errorCode) {
        this(status, errorCode, null, null);
    }

    public AutomationException(HttpStatus status, ErrorCode errorCode, String detail) {
        this(status, errorCode, detail, null);
    }

    public AutomationException(HttpStatus status, ErrorCode errorCode, Throwable cause) {
        this(status, errorCode, null, cause);
    }

    public AutomationException(HttpStatus status, ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.getMessage() + (detail != null ? " - " + detail : ""), cause);
        this.status = status;
        this.errorCode = errorCode;
        this.detail = detail;
    }
}
