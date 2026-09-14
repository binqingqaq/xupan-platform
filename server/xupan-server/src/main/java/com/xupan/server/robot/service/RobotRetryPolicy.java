package com.xupan.server.robot.service;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps retry and processing-lease rules independent from database access.
 */
@Component
public final class RobotRetryPolicy {

    private final int maxAttempts;
    private final Duration processingLease;

    @Autowired
    public RobotRetryPolicy(
            @Value("${xupan.robot.max-attempts:8}") int maxAttempts,
            @Value("${xupan.robot.processing-lease-seconds:30}") long processingLeaseSeconds) {
        this(maxAttempts, Duration.ofSeconds(processingLeaseSeconds));
    }

    RobotRetryPolicy(int maxAttempts, Duration processingLease) {
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("机器人最大重试次数必须在 1 到 100 之间");
        }
        if (processingLease == null || processingLease.isNegative() || processingLease.isZero()
                || processingLease.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("机器人处理租约必须在 1 秒到 10 分钟之间");
        }
        this.maxAttempts = maxAttempts;
        this.processingLease = processingLease;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean canRetry(int attemptCount) {
        return attemptCount >= 0 && attemptCount < maxAttempts;
    }

    public Instant nextAttemptAt(Instant now, int nextAttemptCount) {
        if (now == null || nextAttemptCount < 1) {
            throw new IllegalArgumentException("机器人重试时间参数无效");
        }
        long seconds = 1L << Math.min(nextAttemptCount - 1, 9);
        return now.plusSeconds(Math.min(seconds, 300L));
    }

    public Instant lockedUntil(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("机器人处理时间不能为空");
        }
        return now.plus(processingLease);
    }
}
