package com.xupan.server.displayobserver;

import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisplayObserverRateLimiterTest {

    @Test
    void exchangeIsLimitedPerIpAndPerBindingWithoutDisclosingIdentifiers() {
        DisplayObserverRateLimiter limiter = limiter(2, 3, 4, 8);

        limiter.checkExchange("192.0.2.1", "PublicCodeA");
        limiter.checkExchange("192.0.2.1", "PublicCodeA");

        assertThatThrownBy(() -> limiter.checkExchange("192.0.2.1", "PublicCodeB"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.publicMessage()).contains("请求过于频繁")
                            .doesNotContain("192.0.2.1", "PublicCodeB");
                });
    }

    @Test
    void observerReadLimitIsIndependentPerIpAndPerBinding() {
        DisplayObserverRateLimiter limiter = limiter(8, 8, 2, 3);

        assertThat(limiter.checkRead("192.0.2.1", "PublicCodeA")).isZero();
        assertThat(limiter.checkRead("192.0.2.1", "PublicCodeA")).isZero();
        assertThat(limiter.checkRead("192.0.2.1", "PublicCodeA")).isPositive();
        assertThat(limiter.checkRead("192.0.2.2", "PublicCodeB")).isZero();
    }

    @Test
    void observerWebSocketFrameLimitIsSharedByBinding() {
        DisplayObserverRateLimiter limiter = new DisplayObserverRateLimiter(Duration.ofSeconds(10),
                8, 8, 8, 8, 2,
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));

        assertThat(limiter.checkWebSocketFrame("PublicCodeA")).isZero();
        assertThat(limiter.checkWebSocketFrame("PublicCodeA")).isZero();
        assertThat(limiter.checkWebSocketFrame("PublicCodeA")).isPositive();
        assertThat(limiter.checkWebSocketFrame("PublicCodeB")).isZero();
    }

    private static DisplayObserverRateLimiter limiter(int exchangePerIp, int exchangePerBinding,
                                                      int readsPerIp, int readsPerBinding) {
        return new DisplayObserverRateLimiter(Duration.ofSeconds(10), exchangePerIp, exchangePerBinding,
                readsPerIp, readsPerBinding, Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
    }
}
