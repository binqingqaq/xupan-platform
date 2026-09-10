package com.xupan.server.auth.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AuditRepositoryTest {

    @Autowired
    private LoginAuditRepository loginAuditRepository;

    @Autowired
    private OperationAuditRepository operationAuditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE request_path LIKE '/repo-audit/%'");
        jdbcTemplate.update("DELETE FROM sys_login_log WHERE username_snapshot LIKE 'repo-audit-%'");
    }

    @Test
    void storesDigestsAndTruncatesBoundedFields() {
        String longUsername = "repo-audit-" + "u".repeat(100);
        loginAuditRepository.record(longUsername, null, "FAILURE", "AUTH_INVALID_CREDENTIALS",
                "192.0.2.10", "Mozilla/5.0", Instant.parse("2026-09-10T10:00:00Z"));
        operationAuditRepository.record(null, "PERM_AUDIT_READ", "POST", "/repo-audit/" + "p".repeat(300),
                "resource", "SUCCESS", null, "ordinary request summary", "192.0.2.10",
                Instant.parse("2026-09-10T10:00:00Z"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT LENGTH(username_snapshot) FROM sys_login_log WHERE username_snapshot LIKE 'repo-audit-%'",
                Integer.class)).isEqualTo(64);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ip_digest FROM sys_login_log WHERE username_snapshot LIKE 'repo-audit-%'", String.class))
                .matches("[0-9a-f]{64}");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT LENGTH(request_path) FROM sys_operation_log WHERE request_path LIKE '/repo-audit/%'",
                Integer.class)).isEqualTo(255);
    }

    @Test
    void rejectsSensitiveOperationSummary() {
        assertThatThrownBy(() -> operationAuditRepository.record(null, null, "POST", "/repo-audit/sensitive",
                null, "FAILURE", "BAD_REQUEST", "password=Password123", null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
