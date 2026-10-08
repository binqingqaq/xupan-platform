package com.xupan.server.center.service;

import com.xupan.server.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中心接单面的限流与失败锁定（P0-5）。
 *
 * <p><b>为什么需要</b>：{@code /api/center/**} 在 Spring Security 过滤器链中是
 * {@code permitAll}（放开是因为它用**代理 Token** 认证而不是会话认证，由控制器自校验）。
 * 这带来两个风险：</p>
 * <ul>
 *   <li>Token 可被暴力枚举——没有失败计数，攻击者可以无限试；</li>
 *   <li>接口可被刷爆——没有频率上限，一个失控的代理客户端就能把本地库拖垮。</li>
 * </ul>
 *
 * <p><b>实现选择</b>：单实例内存滑动窗口。不引入 Redis 或 Bucket4j——
 * 用户已确认单实例单进程部署（见规划文档 §1.1），进程内状态不会不一致；
 * 代价是重启后计数清零，对"防暴力枚举"这个目标可以接受。</p>
 *
 * <p><b>两个维度</b>：</p>
 * <ol>
 *   <li><b>按来源（IP + 路径）限流</b>：在认证**之前**调用，统计所有请求，
 *       超限直接 429。这样即使 Token 全错也会先被挡住。</li>
 *   <li><b>按认证失败次数锁定来源</b>：连续失败达到阈值后，该来源在一定时间内
 *       一律 429，不再进入认证比对。这是防枚举的关键。</li>
 * </ol>
 */
@Service
public class CenterRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(CenterRateLimiter.class);

    /** 每个来源在窗口内允许的最大请求数。 */
    private final int maxRequestsPerWindow;
    /** 限流窗口。 */
    private final Duration window;
    /** 允许的连续认证失败次数。 */
    private final int maxAuthFailures;
    /** 触发锁定后的封禁时长。 */
    private final Duration lockDuration;

    private final Clock clock;
    /** 来源 → 请求时间戳队列（滑动窗口）。 */
    private final Map<String, Deque<Instant>> requestWindows = new ConcurrentHashMap<>();
    /** 来源 → 失败状态（次数 + 首次失败时间 + 锁定截止）。 */
    private final Map<String, FailureState> failures = new ConcurrentHashMap<>();

    public CenterRateLimiter(@Value("${xupan.center.rate-limit.max-requests:300}") int maxRequestsPerWindow,
                             @Value("${xupan.center.rate-limit.window:60s}") Duration window,
                             @Value("${xupan.center.rate-limit.max-auth-failures:20}") int maxAuthFailures,
                             @Value("${xupan.center.rate-limit.lock-duration:10m}") Duration lockDuration) {
        this.maxRequestsPerWindow = Math.max(1, maxRequestsPerWindow);
        this.window = window == null || window.isZero() || window.isNegative() ? Duration.ofMinutes(1) : window;
        this.maxAuthFailures = Math.max(1, maxAuthFailures);
        this.lockDuration = lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()
                ? Duration.ofMinutes(10) : lockDuration;
        this.clock = Clock.systemUTC();
    }

    /**
     * 进入接单面的第一道闸：检查来源是否被锁定，并计入请求窗口。
     *
     * <p>必须在 Token 校验**之前**调用。</p>
     *
     * @param source 来源标识（通常是客户端 IP）
     * @throws BusinessException 429：请求过频或来源已被锁定
     */
    public void checkRequest(String source) {
        String key = normalize(source);
        Instant now = clock.instant();

        FailureState state = failures.get(key);
        if (state != null && state.lockedUntil != null && now.isBefore(state.lockedUntil)) {
            long retryAfter = Duration.between(now, state.lockedUntil).toSeconds();
            log.warn("中心接单面来源已锁定，拒绝请求 source={} retryAfterSeconds={}", key, retryAfter);
            throw BusinessException.tooManyRequests("CENTER_SOURCE_LOCKED",
                    "来源已被临时锁定，请稍后重试", retryAfter);
        }

        Deque<Instant> windowQueue = requestWindows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (windowQueue) {
            Instant cutoff = now.minus(this.window);
            while (!windowQueue.isEmpty() && windowQueue.peekFirst().isBefore(cutoff)) {
                windowQueue.pollFirst();
            }
            if (windowQueue.size() >= maxRequestsPerWindow) {
                log.warn("中心接单面请求超频 source={} window={} max={}", key, this.window, maxRequestsPerWindow);
                throw BusinessException.tooManyRequests("CENTER_RATE_LIMITED",
                        "请求过于频繁，请稍后重试", this.window.toSeconds());
            }
            windowQueue.addLast(now);
        }
    }

    /**
     * 记录一次认证失败。
     *
     * <p>达到阈值后锁定该来源。注意**只记失败、不记成功**：一次成功认证会清零计数，
     * 这样正常客户的偶发拼写错误不会累积成封禁。</p>
     */
    public void recordAuthFailure(String source) {
        String key = normalize(source);
        Instant now = clock.instant();
        failures.compute(key, (ignored, existing) -> {
            FailureState state = existing == null ? FailureState.first(now) : existing.increment(now);
            if (state.count >= maxAuthFailures) {
                log.warn("中心接单面认证连续失败达阈值，锁定来源 source={} failures={} lockDuration={}",
                        key, state.count, lockDuration);
                return state.lock(now, lockDuration);
            }
            return state;
        });
    }

    /** 认证成功：清零该来源的失败计数（不解除已生效的锁定，锁定期内成功认证本就走到这里）。 */
    public void recordAuthSuccess(String source) {
        failures.remove(normalize(source));
    }

    /** 当前是否已锁定（供测试与监控断言）。 */
    boolean isLocked(String source) {
        FailureState state = failures.get(normalize(source));
        return state != null && state.lockedUntil != null && clock.instant().isBefore(state.lockedUntil);
    }

    private static String normalize(String source) {
        return source == null || source.isBlank() ? "unknown" : source.trim();
    }

    /** 一个来源的失败状态。 */
    private record FailureState(int count, Instant lockedUntil) {

        static FailureState first(Instant now) {
            return new FailureState(1, null);
        }

        FailureState increment(Instant now) {
            // 锁定已过期则重新开始计数。
            if (lockedUntil != null && now.isAfter(lockedUntil)) {
                return first(now);
            }
            return new FailureState(count + 1, lockedUntil);
        }

        FailureState lock(Instant now, Duration duration) {
            return new FailureState(count, now.plus(duration));
        }
    }
}
