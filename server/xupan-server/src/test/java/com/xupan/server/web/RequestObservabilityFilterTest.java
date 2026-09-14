package com.xupan.server.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class RequestObservabilityFilterTest {

    private final RequestObservabilityFilter filter = new RequestObservabilityFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesRequestIdAndCleansMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(200));

        String requestId = response.getHeader(RequestObservabilityFilter.REQUEST_ID_HEADER);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(request.getAttribute(RequestObservabilityFilter.REQUEST_ID_ATTRIBUTE))
                .isEqualTo(requestId);
        assertThat(MDC.get(RequestObservabilityFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void preservesCanonicalRequestIdAndMakesItAvailableToTheChain() throws Exception {
        String requestId = UUID.randomUUID().toString();
        MockHttpServletRequest request = request("POST", "/api/orders");
        request.addHeader(RequestObservabilityFilter.REQUEST_ID_HEADER, requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get(RequestObservabilityFilter.REQUEST_ID_MDC_KEY)).isEqualTo(requestId);
            ((MockHttpServletResponse) servletResponse).setStatus(201);
        });

        assertThat(response.getHeader(RequestObservabilityFilter.REQUEST_ID_HEADER)).isEqualTo(requestId);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(MDC.get(RequestObservabilityFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void replacesInvalidOrOverlongRequestId() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/orders");
        request.addHeader(RequestObservabilityFilter.REQUEST_ID_HEADER, "not-a-uuid-that-is-too-long");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        String requestId = response.getHeader(RequestObservabilityFilter.REQUEST_ID_HEADER);
        assertThat(requestId).isNotEqualTo("not-a-uuid-that-is-too-long");
        assertThat(UUID.fromString(requestId)).isNotNull();
    }

    @Test
    void logsSafeRequestSummaryWithoutQueryOrSensitiveHeaders(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("GET", "/api/orders");
        request.setQueryString("token=secret-token");
        request.addHeader("Authorization", "Bearer secret-access-token");
        request.addHeader("Cookie", "refresh_token=secret-cookie");
        request.setContent("password=secret-password".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(204));

        String requestId = response.getHeader(RequestObservabilityFilter.REQUEST_ID_HEADER);
        assertThat(output.getOut())
                .contains("HTTP request completed")
                .contains("httpMethod=GET")
                .contains("path=/api/orders")
                .contains("status=204")
                .contains("durationMs=")
                .contains(requestId)
                .doesNotContain("secret-token", "secret-access-token", "secret-cookie", "secret-password")
                .doesNotContain("?token=");
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        return request;
    }
}
