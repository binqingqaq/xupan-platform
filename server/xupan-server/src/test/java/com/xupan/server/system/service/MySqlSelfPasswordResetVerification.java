package com.xupan.server.system.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本机 MySQL 现场验证：管理员重置「自己」的密码。
 *
 * <p>这个场景在 H2 上不会复现锁等待，只有 MySQL 才会出现「业务事务持锁 + 审计独立事务
 * 等同一行外键共享锁」的自锁。用例默认跳过，只有在提供本机 MySQL 凭据时才运行：
 *
 * <pre>
 * $env:XUPAN_DB_PASSWORD = '&lt;docs/本地环境密钥.md 中 xupan_app 的密码&gt;'
 * .\mvnw.cmd -o test "-Dtest=MySqlSelfPasswordResetVerification" -DfailIfNoTests=false
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "XUPAN_DB_PASSWORD", matches = ".+",
        disabledReason = "需要本机 MySQL 凭据 XUPAN_DB_PASSWORD 才会执行")
@TestPropertySource(properties = {
        "xupan.chat.websocket.container-configured=false",
        "xupan.test-player.behavior-enabled=false",
        "xupan.automation.enabled=false"
})
class MySqlSelfPasswordResetVerification {

    private static final String USERNAME = "mysql-self-reset-check";
    private static final String OLD_PASSWORD = "OldPassword123";
    private static final String NEW_PASSWORD = "NewPassword456";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordPolicyService passwordPolicy;
    @Autowired
    private UserAdminService userAdminService;

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void adminCanResetOwnPasswordWithoutLockTimeout() {
        clean();
        long adminId = userRepository.insert(USERNAME, "自助重置验证", passwordPolicy.encode(OLD_PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");

        long startedAt = System.currentTimeMillis();
        userAdminService.resetPassword(adminId, NEW_PASSWORD, adminId);
        long elapsedMs = System.currentTimeMillis() - startedAt;

        String hash = jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, adminId);
        assertThat(passwordPolicy.matches(NEW_PASSWORD, hash)).as("新密码写入 MySQL").isTrue();
        assertThat(passwordPolicy.matches(OLD_PASSWORD, hash)).as("旧密码失效").isFalse();
        assertThat(count("SELECT COUNT(*) FROM sys_operation_log WHERE operator_user_id=? "
                + "AND request_path=? AND result='SUCCESS'", adminId, "/api/admin/users/" + adminId + "/password"))
                .as("审计写入 MySQL").isEqualTo(1);
        assertThat(elapsedMs).as("不应再等待 50 秒锁超时，实际耗时 %d ms", elapsedMs).isLessThan(10_000);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private void clean() {
        jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbc.update("DELETE FROM sys_login_log WHERE username_snapshot = ?", USERNAME);
        jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbc.update("DELETE FROM sys_user WHERE username = ?", USERNAME);
    }
}
