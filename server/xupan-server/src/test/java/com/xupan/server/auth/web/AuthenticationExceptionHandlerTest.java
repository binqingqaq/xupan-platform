package com.xupan.server.auth.web;

import com.xupan.server.web.RequestObservabilityFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AuthenticationExceptionHandlerTest {

    private final AuthenticationExceptionHandler handler = new AuthenticationExceptionHandler();

    @Test
    void correlatesUnexpectedExceptionLogAndErrorResponse(CapturedOutput output) throws Exception {
        String requestId = UUID.randomUUID().toString();
        MockHttpServletRequest request = request("POST", "/api/failure");
        request.setAttribute(RequestObservabilityFilter.REQUEST_ID_ATTRIBUTE, requestId);
        request.addHeader("Authorization", "Bearer secret-access-token");
        request.addHeader("Cookie", "refresh_token=secret-cookie");
        request.setContent("password=secret-password".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(new IllegalStateException("boom"), request, response);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getHeader(RequestObservabilityFilter.REQUEST_ID_HEADER)).isEqualTo(requestId);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"AUTH_INTERNAL_ERROR\"")
                .contains("\"requestId\":\"" + requestId + "\"");
        assertThat(output.getOut())
                .contains(requestId)
                .contains("path=/api/failure")
                .contains("java.lang.IllegalStateException")
                .contains("boom")
                .doesNotContain("secret-access-token", "secret-cookie", "secret-password");
    }

    @Test
    void createsFallbackRequestIdWhenFilterDidNotRun() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/failure");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(new RuntimeException("boom"), request, response);

        String requestId = response.getHeader(RequestObservabilityFilter.REQUEST_ID_HEADER);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(response.getContentAsString()).contains("\"requestId\":\"" + requestId + "\"");
    }

    /**
     * 同类收敛：调用方错误不能落进兜底分支变成 500。
     *
     * <p>以前这些 Spring MVC 标准异常全被 {@code @ExceptionHandler(Exception.class)} 吞成
     * 500 `AUTH_INTERNAL_ERROR`：旧缓存前端请求已下线接口显示成"服务端故障"，缺参/类型不匹配
     * 也一样。现在按类型给出正确的 4xx。</p>
     */
    @Test
    void mapsCallerErrorsToClientStatusesInsteadOf500() throws Exception {
        assertStatus(handler, new NoResourceFoundException(HttpMethod.GET, "/api/display/mobile/draw-board",
                        "No static resource api/display/mobile/draw-board."),
                HttpStatus.NOT_FOUND, "REQUEST_PATH_NOT_FOUND");
        assertStatus(handler, new HttpRequestMethodNotSupportedException("GET"),
                HttpStatus.METHOD_NOT_ALLOWED, "REQUEST_METHOD_NOT_ALLOWED");
        assertStatus(handler, new MissingServletRequestParameterException("machineId", "Long"),
                HttpStatus.BAD_REQUEST, "REQUEST_INVALID");
        assertStatus(handler, new MethodArgumentTypeMismatchException("abc", Long.class, "machineId", null, null),
                HttpStatus.BAD_REQUEST, "REQUEST_INVALID");
        assertStatus(handler, new HttpMediaTypeNotAcceptableException("application/xml"),
                HttpStatus.NOT_ACCEPTABLE, "REQUEST_NOT_ACCEPTABLE");
        assertStatus(handler, new HttpMessageNotReadableException("boom", (HttpInputMessage) null),
                HttpStatus.BAD_REQUEST, "REQUEST_BODY_INVALID");
        assertStatus(handler, new HttpMediaTypeNotSupportedException("text/plain"),
                HttpStatus.BAD_REQUEST, "REQUEST_BODY_INVALID");
    }

    private static void assertStatus(AuthenticationExceptionHandler handler, Exception exception,
                                     HttpStatus expectedStatus, String expectedCode) throws Exception {
        MockHttpServletRequest request = request("POST", "/api/probe");
        MockHttpServletResponse response = new MockHttpServletResponse();

        if (exception instanceof NoResourceFoundException || exception instanceof NoHandlerFoundException) {
            handler.notFound(exception, request, response);
        } else if (exception instanceof HttpRequestMethodNotSupportedException methodException) {
            handler.methodNotSupported(methodException, request, response);
        } else if (exception instanceof HttpMediaTypeNotAcceptableException notAcceptable) {
            handler.notAcceptable(notAcceptable, request, response);
        } else if (exception instanceof HttpMessageNotReadableException
                || exception instanceof HttpMediaTypeNotSupportedException) {
            handler.unreadableRequest(exception, request, response);
        } else {
            handler.badRequestParameter(exception, request, response);
        }

        assertThat(response.getStatus()).isEqualTo(expectedStatus.value());
        assertThat(response.getContentAsString()).contains("\"code\":\"" + expectedCode + "\"");
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        return request;
    }
}
