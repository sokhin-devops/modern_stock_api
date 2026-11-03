package com.modernstock.common;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/**
 * A business error with a message key from i18n/messages_*.properties.
 * The code is also returned to the client, so the web app can react to it (for example INSUFFICIENT_STOCK).
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object[] args;

    public ApiException(HttpStatus status, String code, Object... args) {
        super(code);
        this.status = status;
        this.code = code;
        this.args = args;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what);
    }

    public static ApiException badRequest(String code, Object... args) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, args);
    }

    public static ApiException conflict(String code, Object... args) {
        return new ApiException(HttpStatus.CONFLICT, code, args);
    }

    public static ApiException forbidden(String code, Object... args) {
        return new ApiException(HttpStatus.FORBIDDEN, code, args);
    }

    /** 422: the request is valid but a business rule says no. */
    public static ApiException rule(String code, Object... args) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, args);
    }
}
