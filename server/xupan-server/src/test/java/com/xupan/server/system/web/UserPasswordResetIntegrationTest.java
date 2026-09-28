package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员自助重置密码必须真的改掉密码。
 *
 * <p>回归背景：审计写入曾经用独立事务在业务事务内部执行，而业务事务正持有操作人
 * `sys_user` 行的排他锁，审计 INSERT 的外键检查需要同一行的共享锁，导致自锁并在
 * 50 秒后以锁等待超时失败，整笔事务回滚，于是“刷新后还是旧密码”。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class UserPasswordResetIntegrationTest {

    private static final String USERNAME = "reset-check-admin";
    private static final String OLD_PASSWORD = "OldPassword123";
    private static final String NEW_PASSWORD = "NewPassword456";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordPolicyService passwordPolicy;
    @Autowired
    private OperationAuditRepository auditRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private long adminId;

    @BeforeEach
    void setUp() {
        clean();
        adminId = userRepository.insert(USERNAME, "密码管理员", passwordPolicy.encode(OLD_PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void resetsOwnPasswordRevokesOldSessionAndWritesAudit() throws Exception {
        String token = login(OLD_PASSWORD);

        mockMvc.perform(post("/api/admin/users/" + adminId + "/password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk());

        assertThat(passwordMatches(NEW_PASSWORD)).as("新密码已写入数据库").isTrue();
        assertThat(passwordMatches(OLD_PASSWORD)).as("旧密码已失效").isFalse();

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());

        login(NEW_PASSWORD);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + OLD_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(count("SELECT COUNT(*) FROM sys_operation_log WHERE operator_user_id=? "
                + "AND request_path=? AND result='SUCCESS'", adminId, "/api/admin/users/" + adminId + "/password"))
                .as("重置密码写入成功审计").isEqualTo(1);
    }

    @Test
    void keepsAuditRowWhenBusinessTransactionRollsBack() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            auditRepository.record(adminId, "USER_MANAGE", "POST", "/api/admin/users/" + adminId + "/password",
                    Long.toString(adminId), "FAILURE", "USER_PASSWORD_INVALID", "rollback-test", null, Instant.now());
            throw new IllegalStateException("业务事务回滚");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("SELECT COUNT(*) FROM sys_operation_log WHERE operator_user_id=? "
                + "AND request_summary='rollback-test' AND result='FAILURE'", adminId))
                .as("业务回滚后审计仍然保留").isEqualTo(1);
    }

    private boolean passwordMatches(String rawPassword) {
        String hash = jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, adminId);
        return passwordPolicy.matches(rawPassword, hash);
    }

    private String login(String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
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

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
