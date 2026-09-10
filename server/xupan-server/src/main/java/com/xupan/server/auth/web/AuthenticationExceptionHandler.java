package com.xupan.server.auth.web;

import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.auth.service.TokenService;
import com.xupan.server.web.BusinessException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "请先登录");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "AUTH_PERMISSION_DENIED", "没有访问权限");
    }

    @ExceptionHandler(AuthenticationService.AuthenticationFailure.class)
    public void authenticationFailure(AuthenticationService.AuthenticationFailure exception,
                                      HttpServletResponse response) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, exception.code(), loginMessage(exception.code()));
    }

    @ExceptionHandler(TokenService.InvalidTokenException.class)
    public void invalidToken(TokenService.InvalidTokenException exception,
                             HttpServletResponse response) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_REVOKED", "认证状态已失效");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void invalidRequest(MethodArgumentNotValidException exception,
                               HttpServletResponse response) throws IOException {
        write(response, HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "请求参数无效");
    }

    @ExceptionHandler(BusinessException.class)
    public void businessFailure(BusinessException exception, HttpServletResponse response) throws IOException {
        write(response, exception.status(), exception.code(), exception.publicMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public void invalidBusinessRequest(RuntimeException exception,
                                       HttpServletResponse response) throws IOException {
        write(response, HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "请求无法处理");
    }

    @ExceptionHandler(Exception.class)
    public void unexpected(Exception exception, HttpServletResponse response) throws IOException {
        write(response, HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_INTERNAL_ERROR", "服务暂时不可用");
    }

    private static String loginMessage(String code) {
        return "AUTH_INVALID_CREDENTIALS".equals(code) ? "用户名或密码错误" : "认证状态已失效";
    }

    private static void write(HttpServletResponse response, HttpStatus status,
                              String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String requestId = UUID.randomUUID().toString();
        String json = "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message)
                + "\",\"requestId\":\"" + requestId + "\"}";
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
