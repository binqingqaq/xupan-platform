package com.xupan.server.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@Repository
public class LoginAuditRepository {

    private static final int USERNAME_MAX = 64;
    private static final int FAILURE_CODE_MAX = 64;
    private final JdbcTemplate jdbcTemplate;

    public LoginAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void record(String usernameSnapshot, Long userId, String result, String failureCode,
                       String ip, String userAgent, Instant createdAt) {
        String safeResult = requiredEnum(result, "SUCCESS", "FAILURE", "LOGOUT", "REVOKED");
        String safeFailureCode = rejectSensitive(truncate(failureCode, FAILURE_CODE_MAX), "failureCode");
        jdbcTemplate.update("""
                INSERT INTO sys_login_log
                    (username_snapshot, user_id, result, failure_code, ip_digest, user_agent_digest, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, truncate(Objects.requireNonNull(usernameSnapshot, "usernameSnapshot"), USERNAME_MAX),
                userId, safeResult, safeFailureCode, digest(ip), digest(userAgent), timestamp(createdAt));
    }

    public void recordSuccess(String username, long userId, String ip, String userAgent, Instant createdAt) {
        record(username, userId, "SUCCESS", null, ip, userAgent, createdAt);
    }

    public void recordFailure(String username, Long userId, String failureCode,
                              String ip, String userAgent, Instant createdAt) {
        record(username, userId, "FAILURE", failureCode, ip, userAgent, createdAt);
    }

    public void recordLogout(String username, long userId, String ip, String userAgent, Instant createdAt) {
        record(username, userId, "LOGOUT", null, ip, userAgent, createdAt);
    }

    public void recordRevoked(String username, Long userId, String failureCode,
                              String ip, String userAgent, Instant createdAt) {
        record(username, userId, "REVOKED", failureCode, ip, userAgent, createdAt);
    }

    private static String requiredEnum(String value, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException("非法审计结果");
    }

    static String rejectSensitive(String value, String field) {
        if (value != null && value.matches("(?i).*\\b(password|passwd|token|authorization|secret)\\b.*")) {
            throw new IllegalArgumentException(field + " 不能包含密码或令牌");
        }
        return value;
    }

    static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    static String digest(String value) {
        if (value == null) {
            return null;
        }
        if (value.matches("(?i)[0-9a-f]{64}")) {
            return value.toLowerCase(Locale.ROOT);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : hash) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? new Timestamp(System.currentTimeMillis()) : Timestamp.from(value);
    }
}
