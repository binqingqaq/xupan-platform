package com.xupan.server.auth.web;

import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.auth.service.TokenService;
import com.xupan.server.web.BusinessException;
import com.xupan.server.web.RequestObservabilityFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@RestControllerAdvice
public class AuthenticationExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationExceptionHandler.class);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "请先登录");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "AUTH_PERMISSION_DENIED", "没有访问权限");
    }

    @ExceptionHandler(AuthenticationService.AuthenticationFailure.class)
    public void authenticationFailure(AuthenticationService.AuthenticationFailure exception,
                                      HttpServletRequest request,
                                      HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, exception.code(), loginMessage(exception.code()));
    }

    @ExceptionHandler(TokenService.InvalidTokenException.class)
    public void invalidToken(TokenService.InvalidTokenException exception,
                             HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_REVOKED", "认证状态已失效");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void invalidRequest(MethodArgumentNotValidException exception,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "请求参数无效");
    }

    @ExceptionHandler(BusinessException.class)
    public void businessFailure(BusinessException exception, HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        write(request, response, exception.status(), exception.code(), exception.publicMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public void invalidBusinessRequest(RuntimeException exception,
                                       HttpServletRequest request,
                                       HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "请求无法处理");
    }

    @ExceptionHandler(Exception.class)
    public void unexpected(Exception exception, HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        String requestId = requestId(request);
        log.error("未预期 HTTP 异常 requestId={} httpMethod={} path={} exceptionType={}",
                requestId, request.getMethod(), request.getRequestURI(), exception.getClass().getName(),
                exception);
        write(request, response, HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_INTERNAL_ERROR", "服务暂时不可用");
    }

    private static String loginMessage(String code) {
        return "AUTH_INVALID_CREDENTIALS".equals(code) ? "用户名或密码错误" : "认证状态已失效";
    }

    private static void write(HttpServletRequest request, HttpServletResponse response,
                              HttpStatus status, String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String requestId = requestId(request);
        String json = "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message)
                + "\",\"requestId\":\"" + requestId + "\"}";
        response.setStatus(status.value());
        response.setHeader(RequestObservabilityFilter.REQUEST_ID_HEADER, requestId);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json);
    }

    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestObservabilityFilter.REQUEST_ID_ATTRIBUTE);
        if (attribute instanceof String value && isCanonicalUuid(value)) {
            return value;
        }
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(RequestObservabilityFilter.REQUEST_ID_ATTRIBUTE, requestId);
        return requestId;
    }

    private static boolean isCanonicalUuid(String value) {
        if (value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
