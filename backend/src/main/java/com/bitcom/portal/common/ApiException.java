package com.bitcom.portal.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 서비스 계층이 던지는 업무 예외. 프론트 계약 {error, message} 로 변환된다. */
@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    public static ApiException unauthorized(String code, String message) { return new ApiException(HttpStatus.UNAUTHORIZED, code, message); }
    public static ApiException forbidden(String code, String message) { return new ApiException(HttpStatus.FORBIDDEN, code, message); }
    public static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
    public static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    public static ApiException locked(String code, String message) { return new ApiException(HttpStatus.LOCKED, code, message); }
}
