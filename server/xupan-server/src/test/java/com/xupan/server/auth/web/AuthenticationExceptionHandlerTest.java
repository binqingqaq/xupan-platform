package com.xupan.server.auth.web;

import com.xupan.server.web.RequestObservabilityFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        return request;
    }
}
