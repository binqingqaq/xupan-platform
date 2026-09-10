package com.xupan.server.web;

import org.springframework.http.HttpStatus;

/** Public, stable business error without exposing database or request details. */
public final class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String publicMessage;

    public BusinessException(HttpStatus status, String code, String publicMessage) {
        super(code);
        this.status = status;
        this.code = code;
        this.publicMessage = publicMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String publicMessage() {
        return publicMessage;
    }

    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
