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

    public static BusinessException forbidden(String code, String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, code, message);
    }

    /**
     * 未通过认证（401）。
     *
     * <p>用于非会话类凭据的认证失败，例如中心上游的代理 Token。
     * 注意：调用方应保持**所有失败原因返回同一个 code**，不要把"账号不存在"与"凭据错误"分开，
     * 否则会把账户是否存在暴露给探测方。</p>
     */
    public static BusinessException unauthorized(String code, String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, code, message);
    }
}
