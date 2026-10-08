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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * 请求体缺失/不可解析或媒体类型不支持 → 400（以前落到兜底分支返回 500，排障成本高）。
     *
     * <p>本机实测两个入口：`POST /api/admin/sub-accounts` 省略布尔字段（record + 原始类型）、
     * `POST /api/admin/player-desk/report/login` 完全不传请求体，都会抛
     * {@code HttpMessageNotReadableException}；无 {@code Content-Type} 时抛
     * {@code HttpMediaTypeNotSupportedException}。两者都属于调用方错误，不是服务端故障。</p>
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public void unreadableRequest(Exception exception,
                                  HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.BAD_REQUEST, "REQUEST_BODY_INVALID", "请求体缺失或格式不正确");
    }

    @ExceptionHandler(BusinessException.class)
    public void businessFailure(BusinessException exception, HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        // 429 需要带 Retry-After，让客户端知道多久后可以重试（限流与来源锁定都用它）。
        if (exception.retryAfterSeconds() != null) {
            response.setHeader("Retry-After", Long.toString(exception.retryAfterSeconds()));
        }
        write(request, response, exception.status(), exception.code(), exception.publicMessage());
    }

    /**
     * 路径/静态资源不存在 → 404（同类收敛）。
     *
     * <p>以前这些 Spring MVC 标准异常会被下面的兜底 {@code Exception} 分支吞成 500：本机实测
     * 旧缓存前端请求已下线的 {@code GET /api/display/mobile/draw-board} 就返回 500
     * {@code NoResourceFoundException}，让"前端缓存过期"看起来像服务端故障。</p>
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public void notFound(Exception exception, HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.NOT_FOUND, "REQUEST_PATH_NOT_FOUND", "接口或资源不存在");
    }

    /** 请求方法不支持 → 405（同类收敛，例如对只读接口发 POST）。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotSupported(HttpRequestMethodNotSupportedException exception,
                                   HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.METHOD_NOT_ALLOWED, "REQUEST_METHOD_NOT_ALLOWED", "请求方法不支持");
    }

    /**
     * 缺必填查询参数、参数类型不匹配、缺 multipart 分段 → 400（同类收敛）。
     *
     * <p>这些同样是调用方错误：例如 {@code ?machineId=abc}（类型不匹配）或漏传必填参数。</p>
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class, MultipartException.class})
    public void badRequestParameter(Exception exception, HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "请求参数无效");
    }

    /** 客户端要求的响应类型无法满足 → 406（同类收敛）。 */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public void notAcceptable(HttpMediaTypeNotAcceptableException exception,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.NOT_ACCEPTABLE, "REQUEST_NOT_ACCEPTABLE", "不支持的响应类型");
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
        if ("AUTH_INVALID_CREDENTIALS".equals(code)) return "用户名或密码错误";
        if ("AUTH_SUB_ACCOUNT_EXPIRED".equals(code)) return "账号已过期";
        return "认证状态已失效";
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
