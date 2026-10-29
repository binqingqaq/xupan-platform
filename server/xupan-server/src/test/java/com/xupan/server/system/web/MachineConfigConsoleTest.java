package com.xupan.server.system.web;

import com.jayway.jsonpath.JsonPath;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.game.repository.GameDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 机器级配置在运营后台（{@code /console}，BY220 {@code robot.php}）的读写与生效。
 *
 * <p>BY220 的赔率、特码赔率、返水、特码返水、单边/双边、封盘、取消、限额都是**机器自己的值**
 * （{@code rbt/Index.php:600} 的 {@code needhandle} 把 {@code PeiLv/tePeilv/FanShui/teFanshui/
 * Hold5TimeSeconds/CancelInSecs} 返回给代理后台）。本用例验证：代理在 {@code /console} 改完即写库，
 * 且下注链路读取的就是这份机器配置。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class MachineConfigConsoleTest {

    private static final String ADMIN = "machineconfig-admin";
    private static final String MACHINE_A_USER = "machineconfig-agent-a";
    private static final String MACHINE_B_USER = "machineconfig-agent-b";
    private static final String MACHINE_A_CODE = "MACHINECONFIG_A";
    private static final String MACHINE_B_CODE = "MACHINECONFIG_B";
    private static final String PASSWORD = "MachineConfigPassword123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private GameDataRepository gameDataRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long adminId;
    private long machineAId;
    private long machineBId;

    /** 通过接口创建的玩家用户名由后端自动生成，必须显式登记才能可靠清理。 */
    private final List<Long> createdPlayerUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createdPlayerUserIds.clear();
        clean();
        adminId = userRepository.insert(ADMIN, "机器配置管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");
        machineAId = agentAdminService.createAgent(MACHINE_A_CODE, MACHINE_A_USER, "配置机器A", PASSWORD, null, adminId).id();
        machineBId = agentAdminService.createAgent(MACHINE_B_CODE, MACHINE_B_USER, "配置机器B", PASSWORD, null, adminId).id();
        jdbc.update("UPDATE agent SET rebate_mode='SINGLE' WHERE id IN (?, ?)", machineAId, machineBId);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void machineAccountReadsOwnConfigAndSavesItBack() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        // 下注链路按玩家钱包账户反查机器配置，因此需要一台机器下的真实玩家账户
        long playerAccountId = createPlayerUnderMachineA(token);

        mockMvc.perform(get("/api/admin/player-desk/machine-config").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value((int) machineAId))
                .andExpect(jsonPath("$.machineCode").value(MACHINE_A_CODE))
                .andExpect(jsonPath("$.rebateMode").value("SINGLE"));

        mockMvc.perform(put("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(configBody(null, "95", "18", "5", "1", "DOUBLE", 100, 10, "2000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value((int) machineAId))
                .andExpect(jsonPath("$.oddsRate").value(95.0000))
                .andExpect(jsonPath("$.specialOddsRate").value(18.0000))
                .andExpect(jsonPath("$.rebateMode").value("DOUBLE"))
                .andExpect(jsonPath("$.closeSeconds").value(100))
                .andExpect(jsonPath("$.cancelSeconds").value(10));

        // 显式传别人的 machineId 被按“不存在”拒绝，绝不落到别的机器
        mockMvc.perform(put("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(configBody(machineBId, "11", "11", "1", "1", "SINGLE", 5, 5, "10")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
        assertThat(oddsRateOf(machineAId)).isEqualByComparingTo("95.0000");
        assertThat(oddsRateOf(machineBId)).isEqualByComparingTo("0.0000");

        // 下注链路读取的就是这份机器配置（BY220 机器级赔率优先、全局兜底）
        MachineBettingConfig effective = gameDataRepository.findMachineBettingConfig(playerAccountId);
        assertThat(effective.oddsRate()).isEqualByComparingTo("95.0000");
        assertThat(effective.specialOddsRate()).isEqualByComparingTo("18.0000");
        assertThat(effective.closeSeconds()).isEqualTo(100);
        assertThat(effective.cancelSeconds()).isEqualTo(10);
        assertThat(effective.rebateMode()).isEqualTo("DOUBLE");
    }

    @Test
    void manualBettingStateDefaultsOpenAndOnlyMachineCanChangeOwnState() throws Exception {
        String machineToken = login(MACHINE_A_USER, PASSWORD);
        String otherMachineToken = login(MACHINE_B_USER, PASSWORD);
        String adminToken = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/machine-config").header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualBettingOpen").value(true));

        MvcResult initialList = mockMvc.perform(get("/api/admin/machines")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andReturn();
        assertThat((List<Boolean>) JsonPath.read(initialList.getResponse().getContentAsString(),
                "$[?(@.id == " + machineAId + ")].manualBettingOpen")).containsExactly(true);

        mockMvc.perform(put("/api/admin/player-desk/machine-betting-state")
                        .header("Authorization", bearer(machineToken)).contentType("application/json")
                        .content("{\"machineId\":" + machineAId + ",\"open\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualBettingOpen").value(false));
        mockMvc.perform(get("/api/admin/player-desk/machine-config").header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualBettingOpen").value(false));

        // A repeated desired state is idempotent and does not create duplicate audit entries.
        mockMvc.perform(put("/api/admin/player-desk/machine-betting-state")
                        .header("Authorization", bearer(machineToken)).contentType("application/json")
                        .content("{\"machineId\":" + machineAId + ",\"open\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualBettingOpen").value(false));
        assertThat(auditCount(machineAId)).isEqualTo(1);

        mockMvc.perform(put("/api/admin/player-desk/machine-betting-state")
                        .header("Authorization", bearer(otherMachineToken)).contentType("application/json")
                        .content("{\"machineId\":" + machineAId + ",\"open\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
        mockMvc.perform(put("/api/admin/player-desk/machine-betting-state")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content("{\"machineId\":" + machineAId + ",\"open\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MACHINE_BETTING_STATE_FORBIDDEN"));
        mockMvc.perform(put("/api/admin/player-desk/machine-betting-state")
                        .header("Authorization", bearer(machineToken)).contentType("application/json")
                        .content("{\"machineId\":" + machineAId + "}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT manual_betting_open FROM agent WHERE id=?", Boolean.class, machineAId))
                .isFalse();
        assertThat(auditCount(machineAId)).isEqualTo(1);
    }

    @Test
    void expiredMachineCannotChangeManualBettingState() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        jdbc.update("UPDATE agent SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id = ?", machineAId);
        mockMvc.perform(put("/api/admin/player-desk/machine-betting-state")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"machineId\":" + machineAId + ",\"open\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_ACTIVE"));
        assertThat(jdbc.queryForObject("SELECT manual_betting_open FROM agent WHERE id=?", Boolean.class, machineAId))
                .isTrue();
        assertThat(auditCount(machineAId)).isZero();
    }

    @Test
    void stateToggleWaitsForInFlightBetTransactionMachineRowLock() throws Exception {
        String machineToken = login(MACHINE_A_USER, PASSWORD);
        long playerAccountId = createPlayerUnderMachineA(machineToken);
        CountDownLatch machineRowLocked = new CountDownLatch(1);
        CountDownLatch releaseBet = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> inFlightBet = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        gameDataRepository.lockMachineBettingState(playerAccountId);
                        machineRowLocked.countDown();
                        try {
                            if (!releaseBet.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test did not release the bet transaction");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    }));
            assertThat(machineRowLocked.await(3, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> close = executor.submit(() -> mockMvc.perform(
                            put("/api/admin/player-desk/machine-betting-state")
                                    .header("Authorization", bearer(machineToken)).contentType("application/json")
                                    .content("{\"machineId\":" + machineAId + ",\"open\":false}"))
                    .andReturn());
            Thread.sleep(150);
            assertThat(close.isDone()).isFalse();

            releaseBet.countDown();
            inFlightBet.get(3, TimeUnit.SECONDS);
            assertThat(close.get(3, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT manual_betting_open FROM agent WHERE id=?",
                    Boolean.class, machineAId)).isFalse();
        } finally {
            releaseBet.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 阶段 3（严格对齐 BY220）：机器级配置属于**机器账号**（{@code /console}），
     * 超管不再能通过玩家运营接口读写任意机器的配置，一律 403。
     *
     * <p>BY220 里超管的同类字段走的是「机器管理」表单（{@code rb/form.html} 平移，
     * 即 {@code /api/admin/machines/**}），不是 robot.php 的机器运营面。原用例
     * {@code platformAdminCanReadAndWriteSelectedMachine} 的期望已被业务决定推翻。</p>
     */
    @Test
    void platformAdminCannotReadOrWriteMachineConsoleConfig() throws Exception {
        String adminToken = login(ADMIN, PASSWORD);

        mockMvc.perform(get("/api/admin/player-desk/machine-config").header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/player-desk/machine-config").param("machineId", String.valueOf(machineBId))
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content(configBody(machineBId, "93", "17.5", "4.5", "1", "SINGLE", 60, 0, "1500")))
                .andExpect(status().isForbidden());

        // 被拒不得写库：两台机器的赔率都保持初始值
        assertThat(oddsRateOf(machineBId)).isEqualByComparingTo("0.0000");
        assertThat(oddsRateOf(machineAId)).isEqualByComparingTo("0.0000");
    }

    @Test
    void platformAdminUpdateInvalidatesAStaleMachineConsoleSave() throws Exception {
        String machineToken = login(MACHINE_A_USER, PASSWORD);
        String adminToken = login(ADMIN, PASSWORD);
        MvcResult machineSnapshot = mockMvc.perform(get("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value((int) machineAId))
                .andReturn();
        int staleVersion = ((Number) JsonPath.read(
                machineSnapshot.getResponse().getContentAsString(), "$.configVersion")).intValue();
        String staleMachineSave = configBody(null, "77", "16", "3", "0.25", "DOUBLE", 70, 6, "1200");

        mockMvc.perform(put("/api/admin/machines/" + machineAId)
                        .queryParam("expectedConfigVersion", String.valueOf(staleVersion))
                        .header("Authorization", bearer(adminToken)).contentType("application/json")
                        .content(platformMachineConfigBody("91", "19", "4", "0.75", "1500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(staleVersion + 1))
                .andExpect(jsonPath("$.oddsRate").value(91.0))
                .andExpect(jsonPath("$.totalLimit").value(1500.0));

        mockMvc.perform(put("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(machineToken)).contentType("application/json")
                        .content(staleMachineSave))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MACHINE_CONFIG_VERSION_CONFLICT"));

        mockMvc.perform(get("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(machineToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(staleVersion + 1))
                .andExpect(jsonPath("$.oddsRate").value(91.0))
                .andExpect(jsonPath("$.specialOddsRate").value(19.0))
                .andExpect(jsonPath("$.totalLimit").value(1500.0));
    }

    @Test
    void invalidValuesAreRejectedAsBadRequestAndNothingIsWritten() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);

        mockMvc.perform(put("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(configBody(null, "-1", "18", "5", "1", "SINGLE", 100, 10, "2000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MACHINE_ODDS_RATE_INVALID"));

        mockMvc.perform(put("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(configBody(null, "95", "18", "5", "1", "TRIPLE", 100, 10, "2000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MACHINE_REBATE_MODE_INVALID"));

        mockMvc.perform(put("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(configBody(null, "95", "18", "5", "1", "SINGLE", 100, 10, "2000")
                                .replace("\"playerMinStake\":10,\"playerMaxStake\":100000",
                                        "\"playerMinStake\":50,\"playerMaxStake\":10")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MACHINE_STAKE_RANGE_INVALID"));

        assertThat(oddsRateOf(machineAId)).isEqualByComparingTo("0.0000");
        assertThat(closeSecondsOf(machineAId)).isZero();
    }

    @Test
    void staleMachineConfigVersionIsRejectedWithoutOverwritingTheFirstSave() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        String firstSnapshot = configBody(null, "91", "17", "4", "0.5", "SINGLE", 90, 8, "1800");
        String staleSnapshot = configBody(null, "77", "16", "3", "0.25", "DOUBLE", 70, 6, "1200");

        mockMvc.perform(put("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(firstSnapshot))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oddsRate").value(91.0000))
                .andExpect(jsonPath("$.totalLimit").value(1800.00));

        mockMvc.perform(put("/api/admin/player-desk/machine-config")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content(staleSnapshot))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MACHINE_CONFIG_VERSION_CONFLICT"));

        mockMvc.perform(get("/api/admin/player-desk/machine-config").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oddsRate").value(91.0000))
                .andExpect(jsonPath("$.specialOddsRate").value(17.0000))
                .andExpect(jsonPath("$.rebateMode").value("SINGLE"))
                .andExpect(jsonPath("$.totalLimit").value(1800.00))
                .andExpect(jsonPath("$.closeSeconds").value(90));
    }

    @Test
    void machineAccountCannotReadAnotherMachineConfig() throws Exception {
        String token = login(MACHINE_A_USER, PASSWORD);
        // 机器账号显式传别人的 machineId 时按“不存在”处理：既不静默读成自己，也不暴露别的机器
        mockMvc.perform(get("/api/admin/player-desk/machine-config").param("machineId", String.valueOf(machineBId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));

        // 不传 machineId 时仍固定读自己
        mockMvc.perform(get("/api/admin/player-desk/machine-config").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineId").value((int) machineAId))
                .andExpect(jsonPath("$.machineCode").value(MACHINE_A_CODE));
    }

    private String configBody(Long machineId, String oddsRate, String specialOddsRate, String rebateRate,
                                     String specialRebateRate, String rebateMode, int closeSeconds, int cancelSeconds,
                                     String totalLimit) {
        String machineField = machineId == null ? "" : "\"machineId\":" + machineId + ",";
        long targetMachineId = machineId == null ? machineAId : machineId;
        Integer expectedVersion = jdbc.queryForObject(
                "SELECT betting_config_version FROM agent WHERE id = ?", Integer.class, targetMachineId);
        return "{" + machineField
                + "\"chaseEnabled\":false,\"countdownVisible\":true,\"botCount\":1,"
                + "\"closeSeconds\":" + closeSeconds + ",\"cancelSeconds\":" + cancelSeconds + ","
                + "\"rebateRate\":" + rebateRate + ",\"oddsRate\":" + oddsRate + ","
                + "\"specialRebateRate\":" + specialRebateRate + ",\"specialOddsRate\":" + specialOddsRate + ","
                + "\"rebateMode\":\"" + rebateMode + "\",\"expectedVersion\":" + expectedVersion + ","
                + "\"totalLimit\":" + totalLimit + ",\"positiveLimit\":0,\"angleLimit\":0,\"strictLimit\":0,"
                + "\"tongLimit\":0,\"carLimit\":0,\"specialLimit\":0,\"oddEvenLimit\":0,\"bigSmallLimit\":0,"
                + "\"fanLimit\":0,\"addLimit\":0,\"playerMinStake\":10,\"playerMaxStake\":100000}";
    }

    private String platformMachineConfigBody(String oddsRate, String specialOddsRate, String rebateRate,
                                             String specialRebateRate, String totalLimit) {
        return "{\"username\":\"" + MACHINE_A_USER + "\",\"groupId\":null,\"score\":0,"
                + "\"expiresAt\":null,\"robotManage\":false,\"chaseEnabled\":false,"
                + "\"botCount\":0,\"closeSeconds\":80,\"cancelSeconds\":9,\"rebateRate\":" + rebateRate
                + ",\"oddsRate\":" + oddsRate + ",\"specialRebateRate\":" + specialRebateRate
                + ",\"specialOddsRate\":" + specialOddsRate + ",\"rebateMode\":\"SINGLE\",\"totalLimit\":"
                + totalLimit + ",\"positiveLimit\":0,\"angleLimit\":0,\"strictLimit\":0,\"tongLimit\":0,"
                + "\"carLimit\":0,\"specialLimit\":0,\"oddEvenLimit\":0,\"bigSmallLimit\":0,"
                + "\"fanLimit\":0,\"addLimit\":0,\"playerMaxStake\":100000,\"playerMinStake\":10}";
    }

    private long createPlayerUnderMachineA(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/player-desk/players/normal")
                        .header("Authorization", bearer(token)).contentType("application/json")
                        .content("{\"displayName\":\"配置验证玩家\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long userId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.userId")).longValue();
        createdPlayerUserIds.add(userId);
        Long accountId = jdbc.queryForObject("SELECT id FROM demo_user_account WHERE sys_user_id=?", Long.class, userId);
        return accountId == null ? 0L : accountId;
    }

    private BigDecimal oddsRateOf(long machineId) {
        return jdbc.queryForObject("SELECT odds_rate FROM agent WHERE id=?", BigDecimal.class, machineId);
    }

    private int closeSecondsOf(long machineId) {
        Integer value = jdbc.queryForObject("SELECT close_seconds FROM agent WHERE id=?", Integer.class, machineId);
        return value == null ? 0 : value;
    }

    private long auditCount(long machineId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                 WHERE request_path = '/api/admin/player-desk/machine-betting-state'
                   AND resource_id = ?
                """, Long.class, Long.toString(machineId));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void clean() {
        List<Long> machineIds = jdbc.queryForList("SELECT id FROM agent WHERE agent_code IN (?, ?)",
                Long.class, MACHINE_A_CODE, MACHINE_B_CODE);
        List<Long> users = new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM sys_user WHERE username LIKE 'machineconfig-%'", Long.class));
        users.addAll(createdPlayerUserIds);
        List<Long> accounts = new ArrayList<>();
        if (!machineIds.isEmpty()) {
            accounts.addAll(jdbc.queryForList("SELECT id FROM demo_user_account WHERE agent_id IN ("
                    + placeholders(machineIds.size()) + ")", Long.class, machineIds.toArray()));
        }
        if (!users.isEmpty()) {
            accounts.addAll(jdbc.queryForList("SELECT id FROM demo_user_account WHERE sys_user_id IN ("
                    + placeholders(users.size()) + ")", Long.class, users.toArray()));
        }
        List<Long> allUsers = new ArrayList<>(users);
        if (!accounts.isEmpty()) {
            accounts = accounts.stream().distinct().toList();
            allUsers.addAll(jdbc.queryForList("SELECT sys_user_id FROM demo_user_account WHERE id IN ("
                    + placeholders(accounts.size()) + ")", Long.class, accounts.toArray()));
        }
        List<Long> owners = allUsers.stream().filter(java.util.Objects::nonNull).distinct().toList();

        if (!owners.isEmpty()) {
            delete("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", owners);
            delete("DELETE FROM player_access_link WHERE user_id IN (%s) OR created_by IN (%s)", owners, owners);
            delete("DELETE FROM player_name_change_record WHERE user_id IN (%s)", owners);
            delete("DELETE FROM player_link_expiration_config WHERE user_id IN (%s)", owners);
        }
        if (!accounts.isEmpty()) {
            delete("DELETE FROM demo_balance_ledger WHERE user_id IN (%s)", accounts);
            delete("DELETE FROM test_player_action WHERE account_id IN (%s)", accounts);
            delete("DELETE FROM test_player_behavior WHERE account_id IN (%s)", accounts);
            delete("DELETE FROM demo_user_account WHERE id IN (%s)", accounts);
        }
        if (!machineIds.isEmpty()) {
            delete("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent_ledger WHERE agent_id IN (%s)", machineIds);
        delete("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        if (!owners.isEmpty()) {
            delete("DELETE FROM sys_login_log WHERE user_id IN (%s)", owners);
            delete("DELETE FROM auth_session WHERE user_id IN (%s)", owners);
            delete("DELETE FROM auth_ws_ticket WHERE user_id IN (%s)", owners);
            delete("DELETE FROM sys_user_role WHERE user_id IN (%s)", owners);
            delete("DELETE FROM sys_user WHERE id IN (%s)", owners);
        }
    }

    private void delete(String sqlTemplate, List<Long>... groups) {
        String[] parts = sqlTemplate.split("%s", -1);
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            sql.append(parts[i]);
            if (i < parts.length - 1) {
                List<Long> group = groups[Math.min(i, groups.length - 1)];
                sql.append(placeholders(group.size()));
                args.addAll(group);
            }
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(Math.max(count, 1), "?"));
    }
}
