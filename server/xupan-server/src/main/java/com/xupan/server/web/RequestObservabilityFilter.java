package com.xupan.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Adds a safe request identifier and writes one request completion log entry. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestObservabilityFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String REQUEST_ID_ATTRIBUTE = RequestObservabilityFilter.class.getName()
            + ".requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestObservabilityFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        String previousRequestId = MDC.get(REQUEST_ID_MDC_KEY);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        long startedAt = System.nanoTime();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            logAccess(request, response, requestId, durationMs);
            restoreMdc(previousRequestId);
        }
    }

    private static String resolveRequestId(String candidate) {
        if (candidate != null && isCanonicalUuid(candidate)) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    private static boolean isCanonicalUuid(String value) {
        if (value.length() != 36) {
            return false;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void logAccess(HttpServletRequest request, HttpServletResponse response,
                                  String requestId, long durationMs) {
        int status = response.getStatus();
        String path = request.getRequestURI();
        if ("/actuator/health".equals(path)) {
            if (status >= 400) {
                log.warn("HTTP health request completed requestId={} httpMethod={} path={} status={} durationMs={}",
                        requestId, request.getMethod(), path, status, durationMs);
            } else {
                log.debug("HTTP health request completed requestId={} httpMethod={} path={} status={} durationMs={}",
                        requestId, request.getMethod(), path, status, durationMs);
            }
            return;
        }
        log.info("HTTP request completed requestId={} httpMethod={} path={} status={} durationMs={}",
                requestId, request.getMethod(), path, status, durationMs);
    }

    private static void restoreMdc(String previousRequestId) {
        if (previousRequestId == null) {
            MDC.remove(REQUEST_ID_MDC_KEY);
        } else {
            MDC.put(REQUEST_ID_MDC_KEY, previousRequestId);
        }
    }
}
