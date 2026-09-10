package com.xupan.server.auth.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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

    @Test
    void hashesRawSixtyFourHexMetadataInsteadOfTreatingItAsADigest() throws Exception {
        String rawHexIp = "a".repeat(64);
        String rawHexUserAgent = "b".repeat(64);
        loginAuditRepository.record("repo-audit-hex", null, "FAILURE", "AUTH_INVALID_CREDENTIALS",
                rawHexIp, rawHexUserAgent, Instant.parse("2026-09-10T10:00:00Z"));

        var digests = jdbcTemplate.queryForMap(
                "SELECT ip_digest, user_agent_digest FROM sys_login_log WHERE username_snapshot = ?",
                "repo-audit-hex");
        assertThat(digests.get("IP_DIGEST")).isEqualTo(sha256(rawHexIp));
        assertThat(digests.get("USER_AGENT_DIGEST")).isEqualTo(sha256(rawHexUserAgent));
        assertThat(digests.get("IP_DIGEST")).isNotEqualTo(rawHexIp);
        assertThat(digests.get("USER_AGENT_DIGEST")).isNotEqualTo(rawHexUserAgent);
    }

    private static String sha256(String raw) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
