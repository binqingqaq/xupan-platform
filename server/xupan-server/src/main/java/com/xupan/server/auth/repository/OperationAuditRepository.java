package com.xupan.server.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

@Repository
public class OperationAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public OperationAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void record(Long operatorUserId, String permissionCode, String httpMethod, String requestPath,
                       String resourceId, String result, String errorCode, String requestSummary,
                       String ip, Instant createdAt) {
        String safeSummary = LoginAuditRepository.rejectSensitive(
                LoginAuditRepository.truncate(requestSummary, 2000), "requestSummary");
        String safeResourceId = LoginAuditRepository.rejectSensitive(
                LoginAuditRepository.truncate(resourceId, 128), "resourceId");
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, operatorUserId, LoginAuditRepository.truncate(permissionCode, 128),
                LoginAuditRepository.truncate(Objects.requireNonNull(httpMethod, "httpMethod"), 16),
                LoginAuditRepository.truncate(Objects.requireNonNull(requestPath, "requestPath"), 255), safeResourceId,
                requiredResult(result), LoginAuditRepository.truncate(errorCode, 64), safeSummary,
                LoginAuditRepository.digest(ip), timestamp(createdAt));
    }

    private static String requiredResult(String result) {
        if (!"SUCCESS".equals(result) && !"FAILURE".equals(result)) {
            throw new IllegalArgumentException("非法操作审计结果");
        }
        return result;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? new Timestamp(System.currentTimeMillis()) : Timestamp.from(value);
    }
}
