package com.xupan.server.center.service;

import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-5：中心接单面限流与失败锁定的单元测试。
 *
 * <p>为什么必须测：{@code /api/center/**} 在过滤器链里是 {@code permitAll}，
 * 限流与锁定是这条路径上唯一的防护。若它们失效，Token 可被无限枚举、接口可被刷爆，
 * 而任何集成测试都不会因此变红。</p>
 */
@DisplayName("中心接单面限流与锁定（P0-5）")
class CenterRateLimiterTest {

    private static CenterRateLimiter limiter(int maxRequests, int maxFailures, Duration lock) {
        return new CenterRateLimiter(maxRequests, Duration.ofSeconds(60), maxFailures, lock);
    }

    @Test
    @DisplayName("窗口内未超限时放行")
    void allowsWithinLimit() {
        CenterRateLimiter limiter = limiter(5, 10, Duration.ofMinutes(1));
        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> limiter.checkRequest("1.1.1.1")).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("超过窗口上限返回 429 并带 Retry-After")
    void rejectsWhenOverLimit() {
        CenterRateLimiter limiter = limiter(3, 10, Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) {
            limiter.checkRequest("2.2.2.2");
        }
        assertThatThrownBy(() -> limiter.checkRequest("2.2.2.2"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException failure = (BusinessException) exception;
                    assertThat(failure.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(failure.code()).isEqualTo("CENTER_RATE_LIMITED");
                    assertThat(failure.retryAfterSeconds()).isNotNull().isPositive();
                });
    }

    @Test
    @DisplayName("不同来源各自独立计数，互不影响")
    void countersArePerSource() {
        CenterRateLimiter limiter = limiter(2, 10, Duration.ofMinutes(1));
        limiter.checkRequest("3.3.3.3");
        limiter.checkRequest("3.3.3.3");
        // 3.3.3.3 已用满；另一个来源仍应放行
        assertThatCode(() -> limiter.checkRequest("4.4.4.4")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkRequest("3.3.3.3")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("连续认证失败达阈值后锁定来源，即使请求频率不高")
    void locksSourceAfterRepeatedAuthFailures() {
        // 限流阈值放到很大，确保触发的是"失败锁定"而不是"请求超频"
        CenterRateLimiter limiter = limiter(1000, 3, Duration.ofMinutes(5));
        for (int i = 0; i < 3; i++) {
            limiter.checkRequest("5.5.5.5");
            limiter.recordAuthFailure("5.5.5.5");
        }
        assertThat(limiter.isLocked("5.5.5.5")).isTrue();

        assertThatThrownBy(() -> limiter.checkRequest("5.5.5.5"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code())
                        .isEqualTo("CENTER_SOURCE_LOCKED"));
    }

    @Test
    @DisplayName("认证成功清零失败计数，偶发错误不会累积成封禁")
    void successResetsFailureCounter() {
        CenterRateLimiter limiter = limiter(1000, 3, Duration.ofMinutes(5));
        limiter.recordAuthFailure("6.6.6.6");
        limiter.recordAuthFailure("6.6.6.6");
        limiter.recordAuthSuccess("6.6.6.6");
        // 计数已清零，再失败两次不应锁定
        limiter.recordAuthFailure("6.6.6.6");
        limiter.recordAuthFailure("6.6.6.6");
        assertThat(limiter.isLocked("6.6.6.6")).isFalse();
    }

    @Test
    @DisplayName("空来源归一为 unknown，不会因缺失 IP 而跳过限流")
    void blankSourceIsNormalized() {
        CenterRateLimiter limiter = limiter(1, 10, Duration.ofMinutes(1));
        limiter.checkRequest(null);
        assertThatThrownBy(() -> limiter.checkRequest("  ")).isInstanceOf(BusinessException.class);
    }
}
