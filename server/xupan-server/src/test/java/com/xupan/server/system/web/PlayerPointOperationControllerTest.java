package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.service.VirtualWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class PlayerPointOperationControllerTest {

    private static final String ADMIN = "point-operation-admin";
    private static final String NORMAL = "point-operation-normal";
    private static final String BOT = "point-operation-bot";
    private static final String PASSWORD = "PointOperationPassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private VirtualWalletService walletService;

    private long normalAccountId;
    private long botAccountId;
    private Instant now;

    @BeforeEach
    void setUp() {
        clean();
        long adminId = userRepository.insert(ADMIN, "积分审批管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");

        long normalId = userRepository.insert(NORMAL, "普通积分玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(normalId, "USER");
        normalAccountId = walletService.ensureWalletForUser(normalId, "普通积分玩家");

        long botId = userRepository.insert(BOT, "托积分玩家", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(botId, "USER");
        botAccountId = walletService.ensureWalletForUser(botId, "托积分玩家");
        jdbc.update("UPDATE demo_user_account SET player_kind='BOT' WHERE id=?", botAccountId);

        now = Instant.now();
        insertLedger(normalAccountId, "ADMIN_GRANT", "100.00", "0.00", "100.00", "N-1");
        insertLedger(normalAccountId, "ADMIN_ADJUST", "-25.00", "100.00", "75.00", "N-2");
        insertLedger(normalAccountId, "ADMIN_RESET", "-75.00", "75.00", "0.00", "N-RESET");
        insertLedger(botAccountId, "ADMIN_GRANT", "50.00", "0.00", "50.00", "B-1");
        jdbc.update("UPDATE demo_user_account SET balance=75.00 WHERE id=?", normalAccountId);
        jdbc.update("UPDATE demo_user_account SET balance=50.00 WHERE id=?", botAccountId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void returnsOnlyTopUpAndAdjustForRequestedPlayerKind() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/point-operations/recent")
                        .param("kind", "NORMAL")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("NORMAL"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].displayName").value("普通积分玩家"))
                .andExpect(jsonPath("$.items[0].direction").value("DOWN"))
                .andExpect(jsonPath("$.items[0].amount").value(-25.00))
                .andExpect(jsonPath("$.items[1].direction").value("TOP_UP"))
                .andExpect(jsonPath("$.items[1].amount").value(100.00));

        mockMvc.perform(get("/api/admin/player-desk/point-operations/recent")
                        .param("kind", "BOT")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].displayName").value("托积分玩家"))
                .andExpect(jsonPath("$.items[0].direction").value("TOP_UP"));
    }

    @Test
    void supportsCursorPaginationAndRejectsUnauthorizedUsers() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);

        MvcResult firstPage = mockMvc.perform(get("/api/admin/player-desk/point-operations/recent")
                        .param("kind", "NORMAL").param("limit", "1")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn();
        Number nextBeforeId = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextBeforeId");

        mockMvc.perform(get("/api/admin/player-desk/point-operations/recent")
                        .param("kind", "NORMAL").param("limit", "1")
                        .param("beforeId", String.valueOf(nextBeforeId.longValue()))
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].direction").value("TOP_UP"));

        String normalToken = login(NORMAL, PASSWORD);
        mockMvc.perform(get("/api/admin/player-desk/point-operations/recent")
                        .param("kind", "NORMAL")
                        .header("Authorization", bearer(normalToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvesPendingRequestOnceAndRejectsWithoutChangingBalance() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);
        long approvalId = insertPending(normalAccountId, "TOP_UP", "100.00", "approval-message-1");
        long rejectionId = insertPending(normalAccountId, "DOWN", "25.00", "rejection-message-1");

        mockMvc.perform(get("/api/admin/player-desk/point-requests")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].displayName").value("普通积分玩家"))
                .andExpect(jsonPath("$[0].requestType").value("TOP_UP"));

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + approvalId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"确认上分\"}")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        int ledgerCountAfterApproval = ledgerCount(normalAccountId);
        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + approvalId + "/approve")
                        .contentType("application/json").content("{\"reason\":\"重复确认\"}")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        org.assertj.core.api.Assertions.assertThat(ledgerCount(normalAccountId))
                .isEqualTo(ledgerCountAfterApproval);

        mockMvc.perform(post("/api/admin/player-desk/point-requests/" + rejectionId + "/reject")
                        .contentType("application/json").content("{\"reason\":\"取消下分\"}")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        org.assertj.core.api.Assertions.assertThat(ledgerCount(normalAccountId))
                .isEqualTo(ledgerCountAfterApproval);
        org.assertj.core.api.Assertions.assertThat(walletService.getForAdminHistory(
                        userRepository.findByUsername(NORMAL).orElseThrow().id()).balance())
                .isEqualByComparingTo("175.00");
    }

    private void insertLedger(long accountId, String operation, String amount, String before,
                              String after, String suffix) {
        jdbc.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after,
                     operator_user_id, operator_name, idempotency_key, reason, created_at)
                VALUES (?, ?, ?, ?, ?, NULL, '测试管理员', ?, '最近上下分测试', ?)
                """, accountId, operation, new BigDecimal(amount), new BigDecimal(before),
                new BigDecimal(after), "POINT-OP-" + suffix, Timestamp.from(now));
    }

    private long insertPending(long accountId, String requestType, String amount, String clientMessageId) {
        long userId = jdbc.queryForObject("SELECT sys_user_id FROM demo_user_account WHERE id=?",
                Long.class, accountId);
        jdbc.update("""
                INSERT INTO player_point_request
                    (user_id, request_type, amount, status, client_message_id,
                     source_message_id, requested_at)
                VALUES (?, ?, ?, 'PENDING', ?, 1, ?)
                """, userId, requestType, new BigDecimal(amount), clientMessageId, Timestamp.from(now));
        return jdbc.queryForObject("SELECT id FROM player_point_request WHERE user_id=? AND client_message_id=?",
                Long.class, userId, clientMessageId);
    }

    private int ledgerCount(long accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM demo_balance_ledger WHERE user_id=?",
                Integer.class, accountId);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void clean() {
        jdbc.update("DELETE FROM auth_ws_ticket");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM sys_login_log");
        jdbc.update("DELETE FROM sys_operation_log");
        jdbc.update("DELETE FROM player_point_request WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?)))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username IN (?, ?, ?))", NORMAL, BOT, ADMIN);
        jdbc.update("DELETE FROM sys_user WHERE username IN (?, ?, ?)", NORMAL, BOT, ADMIN);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
