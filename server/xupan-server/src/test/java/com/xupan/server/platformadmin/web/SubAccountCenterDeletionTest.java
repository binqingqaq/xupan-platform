package com.xupan.server.platformadmin.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.platformadmin.service.AccountPurgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SubAccountCenterDeletionTest {

    private static final String ADMIN = "sub-center-delete-admin";
    private static final String PASSWORD = "SubCenterDelete123";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordPolicyService passwords;
    @Autowired private AgentAdminService agents;
    @Autowired private AccountPurgeService purge;

    private long adminId;
    private boolean committedFixture;
    private long cleanupGroupId;
    private long cleanupSubUserId;

    @BeforeEach
    void setUp() {
        adminId = users.insert(ADMIN, ADMIN, passwords.encode(PASSWORD), "ACTIVE");
        users.assignRole(adminId, "ADMIN");
    }

    @AfterEach
    void tearDown() {
        if (!committedFixture) return;
        if (cleanupSubUserId > 0) {
            jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id = ?", cleanupSubUserId);
        }
        if (cleanupGroupId > 0 && jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_group WHERE id = ?", Long.class, cleanupGroupId) > 0) {
            purge.purgeSubAccount(cleanupGroupId);
        }
        purge.purgeLoginAccount(adminId);
    }

    @Test
    void deletesOwnedCenterDataAndUnownedLegacyAttemptsWithSubAccount() throws Exception {
        String token = login();
        long groupId = createGroup(token, "owned");
        long centerId = createCenterAccount(token, groupId, "owned");
        long machineId = createMachine(groupId, "owned");
        long playerId = createPlayer(machineId, "owned");
        long betId = createBet(playerId, "owned");
        long orderId = createCenterOrder(centerId, machineId, betId, "owned", "ACCEPTED");
        long settledOrderId = createCenterOrder(centerId, machineId, betId, "settled", "SETTLED");
        jdbc.update("UPDATE agent SET report_type = 'CENTER', center_agent_id = ? WHERE id = ?",
                centerId, machineId);
        jdbc.update("INSERT INTO center_machine_session (center_agent_id, machine_agent_id, expires_at) "
                + "VALUES (?, ?, ?)", centerId, machineId, Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, center_agent_id, attempt_no, status, report_type, idempotency_key)
                VALUES (?, ?, ?, 1, 'ACCEPTED', 'CENTER', 'SCD-OWNED-ATTEMPT')
                """, betId, machineId, centerId);
        jdbc.update("""
                INSERT INTO report_upstream_order
                    (bet_id, agent_id, attempt_no, status, report_type, idempotency_key)
                VALUES (?, ?, 2, 'TIMEOUT', 'CENTER', 'SCD-LEGACY-ATTEMPT')
                """, betId, machineId);
        jdbc.update("""
                INSERT INTO center_order_disposition
                    (center_order_id, action, from_status, to_status, amount, idempotency_key)
                VALUES (?, 'CONFIRM_ACCEPTED', 'PENDING', 'ACCEPTED', 0, 'SCD-ORDER-DISPOSITION')
                """, orderId);
        jdbc.update("""
                INSERT INTO center_reconciliation_disposition
                    (difference_type, bet_id, resolution, center_agent_id, idempotency_key)
                VALUES ('STATUS_MISMATCH', ?, 'ACKNOWLEDGED', ?, 'SCD-CENTER-RECON')
                """, betId, centerId);
        jdbc.update("""
                INSERT INTO center_reconciliation_disposition
                    (difference_type, bet_id, resolution, idempotency_key)
                VALUES ('MISSING_IN_CENTER', ?, 'ACKNOWLEDGED', 'SCD-LEGACY-RECON')
                """, betId);
        jdbc.update("""
                INSERT INTO center_risk_hit (center_agent_id, rule_code, rule_category)
                VALUES (?, 'CENTER_AMOUNT_LIMIT_EXCEEDED', 'LIMIT')
                """, centerId);
        long otherGroupId = createGroup(token, "other");
        long otherCenterId = createCenterAccount(token, otherGroupId, "other");

        mockMvc.perform(delete("/api/admin/sub-accounts/" + groupId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        assertCount("agent_group", "id", groupId, 0);
        assertCount("center_agent", "id", centerId, 0);
        assertCount("agent", "id", machineId, 0);
        assertCount("demo_user_account", "id", playerId, 0);
        assertCount("game_bet", "id", betId, 0);
        assertCount("center_order", "id", orderId, 0);
        assertCount("center_order", "id", settledOrderId, 0);
        assertCount("center_agent_ledger", "center_agent_id", centerId, 0);
        assertCount("center_machine_session", "center_agent_id", centerId, 0);
        assertCount("center_order_disposition", "center_order_id", orderId, 0);
        assertCount("center_reconciliation_disposition", "bet_id", betId, 0);
        assertCount("center_risk_hit", "center_agent_id", centerId, 0);
        assertCount("report_upstream_order", "bet_id", betId, 0);
        assertCount("agent_group", "id", otherGroupId, 1);
        assertCount("center_agent", "id", otherCenterId, 1);
        String audit = jdbc.queryForObject("""
                SELECT request_summary FROM sys_operation_log
                 WHERE request_path = ? AND http_method = 'DELETE' ORDER BY id DESC LIMIT 1
                """, String.class, "/api/admin/sub-accounts/" + groupId);
        assertThat(audit).contains("centerAccountId=" + centerId, "deletedCenterOrders=2",
                "deletedCenterLedgerRows=1", "purgedMachines=1", "purgedPlayers=1");
    }

    @Test
    void rejectsCrossGroupCenterBindingWithoutDeletingEitherGroup() throws Exception {
        String token = login();
        long targetGroup = createGroup(token, "target");
        long centerId = createCenterAccount(token, targetGroup, "target");
        long otherGroup = createGroup(token, "foreign");
        long foreignMachine = createMachine(otherGroup, "foreign");
        jdbc.update("UPDATE agent SET report_type = 'CENTER', center_agent_id = ? WHERE id = ?",
                centerId, foreignMachine);

        mockMvc.perform(delete("/api/admin/sub-accounts/" + targetGroup)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CENTER_ACCOUNT_CROSS_GROUP_REFERENCE"));

        assertCount("agent_group", "id", targetGroup, 1);
        assertCount("center_agent", "id", centerId, 1);
        assertCount("agent_group", "id", otherGroup, 1);
        assertCount("agent", "id", foreignMachine, 1);
    }

    @Test
    void rejectsTargetMachineBoundToAnotherGroupsCenterAccount() throws Exception {
        String token = login();
        long targetGroup = createGroup(token, "inverse");
        long targetCenter = createCenterAccount(token, targetGroup, "inverse");
        long targetMachine = createMachine(targetGroup, "inverse");
        long otherGroup = createGroup(token, "inverse_other");
        long otherCenter = createCenterAccount(token, otherGroup, "inverse_other");
        jdbc.update("UPDATE agent SET report_type = 'CENTER', center_agent_id = ? WHERE id = ?",
                otherCenter, targetMachine);

        mockMvc.perform(delete("/api/admin/sub-accounts/" + targetGroup)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CENTER_ACCOUNT_CROSS_GROUP_REFERENCE"));

        assertCount("agent_group", "id", targetGroup, 1);
        assertCount("center_agent", "id", targetCenter, 1);
        assertCount("agent", "id", targetMachine, 1);
        assertCount("agent_group", "id", otherGroup, 1);
        assertCount("center_agent", "id", otherCenter, 1);
    }

    @Test
    void deletingOneMachineRemovesItsCenterSessionAndKeepsTheSharedAccount() throws Exception {
        String token = login();
        long groupId = createGroup(token, "machine");
        long centerId = createCenterAccount(token, groupId, "machine");
        long removedMachine = createMachine(groupId, "removed");
        long keptMachine = createMachine(groupId, "kept");
        jdbc.update("INSERT INTO center_machine_session (center_agent_id, machine_agent_id, expires_at) "
                + "VALUES (?, ?, ?)", centerId, removedMachine,
                Timestamp.from(Instant.now().plusSeconds(3600)));

        mockMvc.perform(delete("/api/admin/machines/" + removedMachine)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        assertCount("center_machine_session", "machine_agent_id", removedMachine, 0);
        assertCount("agent", "id", removedMachine, 0);
        assertCount("agent", "id", keptMachine, 1);
        assertCount("center_agent", "id", centerId, 1);
    }

    @Test
    void deletesSubAccountWithoutCenterAccount() throws Exception {
        String token = login();
        long groupId = createGroup(token, "plain");
        long machineId = createMachine(groupId, "plain");

        mockMvc.perform(delete("/api/admin/sub-accounts/" + groupId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        assertCount("agent_group", "id", groupId, 0);
        assertCount("agent", "id", machineId, 0);
    }

    @Test
    void subAccountCannotDeleteItselfAndAdminCanDeleteItsCenterAccount() throws Exception {
        String adminToken = login();
        long groupId = createGroup(adminToken, "self", true);
        long centerId = createCenterAccount(adminToken, groupId, "self");
        String subToken = login("scd_sub_self", PASSWORD);

        mockMvc.perform(delete("/api/admin/sub-accounts/" + groupId)
                        .header("Authorization", bearer(subToken)))
                .andExpect(status().isForbidden());

        assertCount("agent_group", "id", groupId, 1);
        assertCount("center_agent", "id", centerId, 1);
        mockMvc.perform(delete("/api/admin/sub-accounts/" + groupId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());

        assertCount("agent_group", "id", groupId, 0);
        assertCount("center_agent", "id", centerId, 0);
    }

    @Test
    void deletingAllAccountsClearsBoundCenterBeforeMachines() throws Exception {
        String token = login();
        long groupId = createGroup(token, "all");
        long centerId = createCenterAccount(token, groupId, "all");
        long machineId = createMachine(groupId, "all");
        jdbc.update("INSERT INTO center_machine_session (center_agent_id, machine_agent_id, expires_at) "
                + "VALUES (?, ?, ?)", centerId, machineId,
                Timestamp.from(Instant.now().plusSeconds(3600)));

        purge.purgeEverythingExcept(adminId);

        assertCount("agent_group", "id", groupId, 0);
        assertCount("center_agent", "id", centerId, 0);
        assertCount("center_machine_session", "machine_agent_id", machineId, 0);
        assertCount("agent", "id", machineId, 0);
        assertCount("sys_user", "id", adminId, 1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lateDeleteFailureRollsBackCenterMachineAndGroup() throws Exception {
        committedFixture = true;
        String token = login();
        long groupId = createGroup(token, "rollback");
        cleanupGroupId = groupId;
        long centerId = createCenterAccount(token, groupId, "rollback");
        long machineId = createMachine(groupId, "rollback");
        long subUserId = jdbc.queryForObject("SELECT sys_user_id FROM agent_group WHERE id = ?",
                Long.class, groupId);
        cleanupSubUserId = subUserId;
        jdbc.update("""
                INSERT INTO demo_user_account
                    (user_code, member_code, display_name, balance, sys_user_id)
                VALUES ('SCD_PLAYER_ROLLBACK', 'SCD-MEMBER-ROLLBACK', '保留账号引用以触发后段失败', 0, ?)
                """, subUserId);

        mockMvc.perform(delete("/api/admin/sub-accounts/" + groupId).header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURGE_ORDER_INVALID"));

        assertCount("agent_group", "id", groupId, 1);
        assertCount("center_agent", "id", centerId, 1);
        assertCount("center_agent_ledger", "center_agent_id", centerId, 1);
        assertCount("agent", "id", machineId, 1);
        assertCount("demo_user_account", "sys_user_id", subUserId, 1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCenterCreationAndSubAccountDeletionLeaveNoOrphan() throws Exception {
        committedFixture = true;
        String token = login();
        long groupId = createGroup(token, "race");
        cleanupGroupId = groupId;
        createMachine(groupId, "race");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> createStatus = executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/admin/center/accounts")
                                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"groupId\":" + groupId
                                        + ",\"username\":\"SCD_CENTER_race\",\"password\":\"" + PASSWORD
                                        + "\",\"initialQuota\":250}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> deleteStatus = executor.submit(() -> {
                start.await();
                return mockMvc.perform(delete("/api/admin/sub-accounts/" + groupId)
                                .header("Authorization", bearer(token)))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(deleteStatus.get(15, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(createStatus.get(15, TimeUnit.SECONDS)).isIn(200, 404);
        } finally {
            executor.shutdownNow();
        }
        assertCount("agent_group", "id", groupId, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM center_agent WHERE agent_code = ?",
                Long.class, "SCD_CENTER_race")).isZero();
    }

    private String login() throws Exception {
        return login(ADMIN, PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        MvcResult response = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(response.getResponse().getContentAsString(), "$.accessToken");
    }

    private long createGroup(String token, String suffix) throws Exception {
        return createGroup(token, suffix, false);
    }

    private long createGroup(String token, String suffix, boolean subAccountManage) throws Exception {
        MvcResult response = mockMvc.perform(post("/api/admin/sub-accounts")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"scd_sub_" + suffix + "\",\"rawPassword\":\"" + PASSWORD
                                + "\",\"score\":1000,\"subAccountManage\":" + subAccountManage
                                + ",\"machineManage\":true}"))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(response.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long createCenterAccount(String token, long groupId, String suffix) throws Exception {
        MvcResult response = mockMvc.perform(post("/api/admin/center/accounts")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + groupId + ",\"username\":\"SCD_CENTER_" + suffix
                                + "\",\"password\":\"" + PASSWORD + "\",\"initialQuota\":250}"))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(response.getResponse().getContentAsString(), "$.account.accountId"))
                .longValue();
    }

    private long createMachine(long groupId, String suffix) {
        return agents.createAgent("SCD_MACHINE_" + suffix, "scd_machine_" + suffix,
                "子账号中心删除测试机器", PASSWORD, groupId, adminId).id();
    }

    private long createPlayer(long machineId, String suffix) {
        jdbc.update("""
                INSERT INTO demo_user_account (user_code, member_code, display_name, balance, agent_id)
                VALUES (?, ?, '子账号中心删除测试玩家', 100, ?)
                """, "SCD_PLAYER_" + suffix, "SCD-MEMBER-" + suffix, machineId);
        return jdbc.queryForObject("SELECT id FROM demo_user_account WHERE user_code = ?", Long.class,
                "SCD_PLAYER_" + suffix);
    }

    private long createBet(long playerId, String suffix) {
        jdbc.update("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number, ball_number,
                     play_type, parameters_text, stake, odds_snapshot, settlement_status)
                VALUES ('AU8', ?, ?, ?, '3000001', 1, 'FAN', '1', 10, 3.850, 'PENDING')
                """, playerId, "SCD-BET-" + suffix, "SCD-BET-KEY-" + suffix);
        return jdbc.queryForObject("SELECT id FROM game_bet WHERE bet_code = ?", Long.class,
                "SCD-BET-" + suffix);
    }

    private long createCenterOrder(long centerId, long machineId, long betId, String suffix, String status) {
        jdbc.update("""
                INSERT INTO center_order
                    (center_agent_id, agent_order_no, local_bet_id, local_agent_id, game_code,
                     issue_number, play_type, play_text, odds, amount, status, frozen_amount,
                     risk_amount, risk_policy_version, machine_funded_amount,
                     settle_result, settle_amount, draw_numbers, accepted_at, settled_at)
                VALUES (?, ?, ?, ?, 'AU8', '3000001', 'FAN', 'params=1', 3.850, 10,
                        ?, 10, 200, 1, 100,
                        CASE WHEN ? = 'SETTLED' THEN 'AGENT_LOSE' ELSE NULL END,
                        CASE WHEN ? = 'SETTLED' THEN 10 ELSE NULL END,
                        CASE WHEN ? = 'SETTLED' THEN '1,2,3,4,5,6,7,8' ELSE NULL END,
                        CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'SETTLED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, centerId, "SCD-ORDER-" + suffix, betId, machineId,
                status, status, status, status, status);
        return jdbc.queryForObject("SELECT id FROM center_order WHERE agent_order_no = ?", Long.class,
                "SCD-ORDER-" + suffix);
    }

    private void assertCount(String table, String column, long id, long expected) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class, id)).as(table + "." + column).isEqualTo(expected);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
