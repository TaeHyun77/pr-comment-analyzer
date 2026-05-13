package com.pr.automation.common.error;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class ErrorDto {

    private final String code;
    private final String msg;
    private final String detail;

    public static ErrorDto from(AutomationException e) {
        return new ErrorDto(e.getErrorCode().name(), e.getErrorCode().getMessage(), e.getDetail());
    }

    public static ErrorDto of(String code, String msg) {
        return new ErrorDto(code, msg, null);
    }
}
