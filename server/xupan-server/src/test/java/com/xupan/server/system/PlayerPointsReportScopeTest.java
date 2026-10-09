package com.xupan.server.system;

import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.system.service.PlayerPointsReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下分报表的**范围过滤回归**：MACHINE 与 GROUP 两个非全局范围都必须真的查到数据。
 *
 * <p>缺陷背景（本用例就是为了锁死它）：{@code PlayerPointsReportRepository} 的
 * {@code findBetEvents / findLedgerEvents / findActionEvents} 三个查询把
 * {@code perPlayerLimit} 放在 {@code scope.agentIdParameters()} **之前**，而 SQL 里的占位符顺序是
 * "player_kind → 日期窗口 → 范围(agent_id) → row_no"。GLOBAL 范围的 {@code agentIdParameters()} 为空，
 * 参数顺序错位看不出来；**MACHINE / GROUP 范围下 {@code perPlayerLimit} 会被绑到 {@code agent_id}、
 * {@code agentId} 被绑到 {@code row_no}**，三个查询全部返回空 → 报表全是 0。
 * 参数顺序修正后，本用例（以及 {@code PlayerPointsReportControllerTest} 的 MACHINE 用例）才会通过；
 * **把参数顺序改回旧写法，本用例必定失败**（turnover/topUp/botActions 全部变成 0）。</p>
 *
 * <p>为什么走服务层直调而不是 HTTP：阶段 3 之后 {@code /api/admin/player-desk/**} 只认机器账号，
 * 子账号（GROUP 范围）在 URL 层就 403，GROUP 范围只能从服务层覆盖
 * （与 {@code UnifiedReportInheritanceTest} 的 service 直调同一手法）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
class PlayerPointsReportScopeTest {

    private static final String ADMIN = "pprs-admin";
    private static final String SUB_ACCOUNT = "pprs-sub";
    private static final String MACHINE_A_USER = "pprs-machine-a";
    private static final String MACHINE_B_USER = "pprs-machine-b";
    private static final String NORMAL_A = "pprs-normal-a";
    private static final String NORMAL_B = "pprs-normal-b";
    private static final String BOT_A = "pprs-bot-a";
    private static final String BOT_B = "pprs-bot-b";
    private static final String GROUP_CODE = "PPRS_GROUP";
    private static final String MACHINE_A_CODE = "PPRS_A";
    private static final String MACHINE_B_CODE = "PPRS_B";
    private static final String PASSWORD = "PointsScopePassword123";
    private static final String BUSINESS_DATE = "2026-09-21";
    private static final Instant IN_DAY = Instant.parse("2026-09-21T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordPolicyService passwordPolicy;

    @Autowired
    private VirtualWalletService walletService;

    @Autowired
    private AgentAdminService agentAdminService;

    @Autowired
    private PlayerPointsReportService reportService;

    private long groupSubAccountId;
    private long machineAId;
    private long machineBId;
    private long machineAUserId;
    private long machineBUserId;
    private long normalAAccountId;
    private long normalBAccountId;
    private long botAAccountId;
    private long botBAccountId;

    @BeforeEach
    void setUp() {
        clean();
        long adminId = userRepository.insert(ADMIN, "报表范围管理员", passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(adminId, "ADMIN");

        long groupId = agentAdminService.createGroup(GROUP_CODE, "报表范围分组", adminId).id();
        machineAId = agentAdminService.createAgent(MACHINE_A_CODE, MACHINE_A_USER, "范围机器A", PASSWORD, groupId, adminId).id();
        machineBId = agentAdminService.createAgent(MACHINE_B_CODE, MACHINE_B_USER, "范围机器B", PASSWORD, null, adminId).id();
        machineAUserId = jdbc.queryForObject("SELECT account_user_id FROM agent WHERE id = ?", Long.class, machineAId);
        machineBUserId = jdbc.queryForObject("SELECT account_user_id FROM agent WHERE id = ?", Long.class, machineBId);

        // 子账号绑定到分组 → resolve() 得到 GROUP 范围
        groupSubAccountId = userRepository.insert(SUB_ACCOUNT, "报表范围子账号",
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(groupSubAccountId, "SUB_ACCOUNT");
        jdbc.update("UPDATE agent_group SET sys_user_id = ? WHERE id = ?", groupSubAccountId, groupId);

        normalAAccountId = createPlayer(NORMAL_A, "组内普通玩家", "NORMAL", machineAId);
        normalBAccountId = createPlayer(NORMAL_B, "组外普通玩家", "NORMAL", machineBId);
        botAAccountId = createPlayer(BOT_A, "组内托", "BOT", machineAId);
        botBAccountId = createPlayer(BOT_B, "组外托", "BOT", machineBId);

        // 组内普通玩家：注单 30（turnover）+ 上分 100 / 下分 25（topUp/down + pointOperations）
        insertBet(normalAAccountId, "PPRS-BET-A", "30.00", "12.00");
        insertLedger(normalAAccountId, "ADMIN_GRANT", "100.00", "0.00", "100.00", "A-UP");
        insertLedger(normalAAccountId, "ADMIN_ADJUST", "-25.00", "100.00", "75.00", "A-DOWN");
        // 组外普通玩家：注单 50 + 上分 50（绝不能出现在 GROUP 报表里）
        insertBet(normalBAccountId, "PPRS-BET-B", "50.00", "20.00");
        insertLedger(normalBAccountId, "ADMIN_GRANT", "50.00", "0.00", "50.00", "B-UP");
        // 两个托各有一条行为动作
        insertBotAction(botAAccountId, "PPRS-ACT-A", "组内托动作");
        insertBotAction(botBAccountId, "PPRS-ACT-B", "组外托动作");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /** GROUP 范围：三个事件查询都必须查到本组数据（旧参数顺序下全是空）。 */
    @Test
    void groupScopeReportReadsBetsLedgersAndBotActions() {
        PlayerPointsReportService.Report normal =
                reportService.report("NORMAL", BUSINESS_DATE, 500, groupSubAccountId);

        assertThat(normal.summary().playerCount()).isEqualTo(1);
        assertThat(normal.summary().turnover()).isEqualByComparingTo("30.00");
        assertThat(normal.summary().topUp()).isEqualByComparingTo("100.00");
        assertThat(normal.summary().down()).isEqualByComparingTo("25.00");
        assertThat(normal.players()).singleElement().satisfies(card -> {
            assertThat(card.displayName()).isEqualTo("组内普通玩家");
            assertThat(card.bets()).hasSize(1);
            assertThat(card.pointOperations()).hasSize(2);
        });

        PlayerPointsReportService.Report bots =
                reportService.report("BOT", BUSINESS_DATE, 500, groupSubAccountId);
        assertThat(bots.summary().playerCount()).isEqualTo(1);
        assertThat(bots.players()).singleElement().satisfies(card -> {
            assertThat(card.displayName()).isEqualTo("组内托");
            assertThat(card.botActions()).hasSize(1);
            assertThat(card.botActions().get(0).status()).isEqualTo("SUCCEEDED");
        });

        // 范围隔离：组外玩家与组外托绝不出现在 GROUP 报表里
        assertThat(normal.players()).extracting(PlayerPointsReportService.Card::displayName)
                .doesNotContain("组外普通玩家");
        assertThat(bots.players()).extracting(PlayerPointsReportService.Card::displayName)
                .doesNotContain("组外托");
    }

    /** MACHINE 范围：三个事件查询同样必须查到自己那台机器的数据。 */
    @Test
    void machineScopeReportReadsBetsLedgersAndBotActions() {
        PlayerPointsReportService.Report normal =
                reportService.report("NORMAL", BUSINESS_DATE, 500, machineBUserId);

        assertThat(normal.summary().playerCount()).isEqualTo(1);
        assertThat(normal.summary().turnover()).isEqualByComparingTo("50.00");
        assertThat(normal.summary().topUp()).isEqualByComparingTo("50.00");
        assertThat(normal.summary().down()).isEqualByComparingTo("0.00");
        assertThat(normal.players()).singleElement().satisfies(card -> {
            assertThat(card.displayName()).isEqualTo("组外普通玩家");
            assertThat(card.bets()).hasSize(1);
            assertThat(card.pointOperations()).hasSize(1);
        });

        PlayerPointsReportService.Report bots =
                reportService.report("BOT", BUSINESS_DATE, 500, machineBUserId);
        assertThat(bots.summary().playerCount()).isEqualTo(1);
        assertThat(bots.players()).singleElement().satisfies(card -> {
            assertThat(card.displayName()).isEqualTo("组外托");
            assertThat(card.botActions()).hasSize(1);
        });

        assertThat(normal.players()).extracting(PlayerPointsReportService.Card::displayName)
                .doesNotContain("组内普通玩家");
    }

    private long createPlayer(String username, String displayName, String playerKind, long agentId) {
        long userId = userRepository.insert(username, displayName, passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(userId, "USER");
        long accountId = walletService.ensureWalletForUser(userId, displayName);
        jdbc.update("UPDATE demo_user_account SET player_kind = ?, agent_id = ? WHERE id = ?",
                playerKind, agentId, accountId);
        return accountId;
    }

    private void insertBet(long accountId, String betCode, String stake, String netProfit) {
        jdbc.update("""
                INSERT INTO game_bet
                    (user_id, bet_code, request_idempotency_key, issue_number, ball_number, play_type,
                     parameters_text, stake, odds_snapshot, settlement_status, net_profit, explanation,
                     created_at, settled_at)
                VALUES (?, ?, ?, '20260921', 1, 'FAN', '1', ?, 1.950, 'WIN', ?, '测试结算', ?, ?)
                """, accountId, betCode, betCode, new BigDecimal(stake), new BigDecimal(netProfit),
                Timestamp.from(IN_DAY), Timestamp.from(IN_DAY));
    }

    private void insertLedger(long accountId, String operation, String amount, String before,
                              String after, String suffix) {
        jdbc.update("""
                INSERT INTO demo_balance_ledger
                    (user_id, operation_type, amount, balance_before, balance_after,
                     operator_user_id, operator_name, idempotency_key, reason, created_at)
                VALUES (?, ?, ?, ?, ?, NULL, '测试管理员', ?, '报表范围测试', ?)
                """, accountId, operation, new BigDecimal(amount), new BigDecimal(before),
                new BigDecimal(after), "PPRS-" + suffix, Timestamp.from(IN_DAY));
    }

    private void insertBotAction(long accountId, String actionKey, String sourceText) {
        jdbc.update("""
                INSERT INTO test_player_behavior(account_id, run_mode, enabled, stake_min, stake_max)
                VALUES (?, 'MANUAL', FALSE, 100.00, 100.00)
                """, accountId);
        long behaviorId = jdbc.queryForObject(
                "SELECT id FROM test_player_behavior WHERE account_id = ?", Long.class, accountId);
        long userId = jdbc.queryForObject(
                "SELECT sys_user_id FROM demo_user_account WHERE id = ?", Long.class, accountId);
        jdbc.update("""
                INSERT INTO test_player_action
                    (behavior_id, user_id, account_id, issue_number, action_no, action_type,
                     idempotency_key, source_text, status, created_at, updated_at)
                VALUES (?, ?, ?, '20260921', 1, 'BET_TEXT', ?, ?, 'SUCCEEDED', ?, ?)
                """, behaviorId, userId, accountId, actionKey, sourceText,
                Timestamp.from(IN_DAY), Timestamp.from(IN_DAY));
    }

    private void clean() {
        List<Long> groupIds = jdbc.queryForList(
                "SELECT id FROM agent_group WHERE group_code = ?", Long.class, GROUP_CODE);
        List<Long> machineIds = jdbc.queryForList(
                "SELECT id FROM agent WHERE agent_code IN (?, ?)", Long.class, MACHINE_A_CODE, MACHINE_B_CODE);
        List<Long> userIds = new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM sys_user WHERE username IN (?, ?, ?, ?, ?, ?, ?)", Long.class,
                ADMIN, SUB_ACCOUNT, MACHINE_A_USER, MACHINE_B_USER, NORMAL_A, NORMAL_B, BOT_A));
        userIds.addAll(jdbc.queryForList(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, BOT_B));
        userIds = userIds.stream().filter(java.util.Objects::nonNull).distinct().collect(java.util.stream.Collectors.toList());

        List<Long> accountIds = machineIds.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(jdbc.queryForList("SELECT id FROM demo_user_account WHERE agent_id IN ("
                        + placeholders(machineIds.size()) + ")", Long.class, machineIds.toArray()));

        if (!accountIds.isEmpty()) {
            delete("DELETE FROM test_player_action WHERE account_id IN (%s)", accountIds);
            delete("DELETE FROM test_player_behavior WHERE account_id IN (%s)", accountIds);
            delete("DELETE FROM demo_balance_ledger WHERE user_id IN (%s)", accountIds);
            delete("DELETE FROM game_bet WHERE user_id IN (%s)", accountIds);
            delete("DELETE FROM demo_user_account WHERE id IN (%s)", accountIds);
        }
        if (!userIds.isEmpty()) {
            delete("DELETE FROM test_player_action WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM sys_operation_log WHERE operator_user_id IN (%s)", userIds);
            delete("DELETE FROM sys_login_log WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM auth_session WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM auth_ws_ticket WHERE user_id IN (%s)", userIds);
        }
        if (!machineIds.isEmpty()) {
            delete("DELETE FROM agent_game WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent_ledger WHERE agent_id IN (%s)", machineIds);
            delete("DELETE FROM agent WHERE id IN (%s)", machineIds);
        }
        if (!groupIds.isEmpty()) {
            delete("UPDATE agent_group SET sys_user_id = NULL WHERE id IN (%s)", groupIds);
            delete("DELETE FROM agent_ledger WHERE group_id IN (%s)", groupIds);
            delete("DELETE FROM agent_group WHERE id IN (%s)", groupIds);
        }
        if (!userIds.isEmpty()) {
            delete("DELETE FROM sys_user_role WHERE user_id IN (%s)", userIds);
            delete("DELETE FROM sys_user WHERE id IN (%s)", userIds);
        }
    }

    private void delete(String sqlTemplate, List<Long> ids) {
        jdbc.update(sqlTemplate.replace("%s", placeholders(ids.size())), ids.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(Math.max(count, 1), "?"));
    }
}
