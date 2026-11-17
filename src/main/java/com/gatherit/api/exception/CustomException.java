package com.gatherit.api.exception;

import lombok.Getter;

/**
 * 도메인 예외 — ErrorCode 만 받아 던짐. GlobalExceptionHandler 가 status/body 변환.
 */
@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public CustomException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }
}
