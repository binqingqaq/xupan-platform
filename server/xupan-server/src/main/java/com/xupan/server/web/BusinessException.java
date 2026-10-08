package com.xupan.server.web;

import org.springframework.http.HttpStatus;

/** Public, stable business error without exposing database or request details. */
public final class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String publicMessage;
    /** 建议客户端等待的秒数（429 时使用）；{@code null} 表示不提供。 */
    private final Long retryAfterSeconds;

    public BusinessException(HttpStatus status, String code, String publicMessage) {
        this(status, code, publicMessage, null);
    }

    public BusinessException(HttpStatus status, String code, String publicMessage, Long retryAfterSeconds) {
        super(code);
        this.status = status;
        this.code = code;
        this.publicMessage = publicMessage;
        this.retryAfterSeconds = retryAfterSeconds;
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

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
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

    /**
     * 请求过频或来源被锁（429）。
     *
     * @param retryAfterSeconds 建议客户端等待的秒数，会作为 {@code Retry-After} 响应头下发
     */
    public static BusinessException tooManyRequests(String code, String message, long retryAfterSeconds) {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, code, message,
                Math.max(1L, retryAfterSeconds));
    }
}
