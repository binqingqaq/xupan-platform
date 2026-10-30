package com.xupan.server.chat;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.service.BetLimitExceededException;
import com.xupan.server.game.service.DemoGameService;
import com.xupan.server.game.service.PlayerBetLock;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.robot.service.RobotNoticeDispatchService;
import com.xupan.server.game.web.PlaceBetRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "xupan.automation.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatMessageFeedbackIntegrationTest {

    private static final String USERNAME = "chat-feedback-integration";
    private static final String DISPLAY_NAME = "反馈测试用户";
    private static final String PASSWORD = "ChatFeedback123";

    @Autowired
    private ChatMessageService messageService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordPolicyService passwordPolicy;
    @Autowired
    private VirtualWalletService walletService;
    @Autowired
    private DemoGameService gameService;
    @Autowired
    private PlayerBetLock playerBetLock;
    @Autowired
    private RobotNoticeDispatchService noticeDispatchService;

    private long userId;
    private long accountId;
    private long machineId;

    @BeforeEach
    void setUp() {
        clean();
        resetBettingConfig();
        userId = userRepository.insert(USERNAME, DISPLAY_NAME,
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(userId, "USER");
        accountId = walletService.ensureWalletForUser(userId, DISPLAY_NAME);
        jdbcTemplate.update("""
                INSERT INTO agent (agent_code, display_name, account_user_id, system_owned, status, bot_count)
                VALUES ('CHAT-FEEDBACK-MACHINE', '聊天反馈测试机器', ?, FALSE, 'ACTIVE', 0)
                """, userId);
        machineId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent WHERE agent_code = 'CHAT-FEEDBACK-MACHINE'", Long.class);
        jdbcTemplate.update("UPDATE demo_user_account SET agent_id = ? WHERE id = ?", machineId, accountId);
        configureMachine();
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch");
        jdbcTemplate.update("DELETE FROM game_issue_event");
        jdbcTemplate.update("DELETE FROM game_issue WHERE draw_confirmed_by IS NULL");
    }

    @AfterEach
    void tearDown() {
        clean();
        resetBettingConfig();
    }

    @Test
    @Order(1)
    void concurrentChatBetsCannotBreakTheSamePlayerQuota() throws Exception {
        walletService.grant(userId, userId, new BigDecimal("1000.00"), "并发限额测试充值",
                "CHAT-FEEDBACK-GRANT-CONCURRENT");
        setFanLimit(25);

        int attempts = 4;
        var pool = Executors.newFixedThreadPool(attempts);
        var start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            int attempt = index;
            futures.add(pool.submit(() -> {
                start.await();
                // 聊天室事务全程持有 chat_room 行锁，同一大厅的下注请求天然按提交顺序串行。
                return messageService.sendUserMessage(userId, "main", "concurrent-limit-" + attempt,
                        "1番10", Instant.now());
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertQuotaNotBroken();
    }

    @Test
    @Order(2)
    void concurrentDirectBetsCannotBreakTheSamePlayerQuota() throws Exception {
        walletService.grant(userId, userId, new BigDecimal("1000.00"), "并发直连接口测试充值",
                "CHAT-FEEDBACK-GRANT-CONCURRENT-DIRECT");
        setFanLimit(25);

        int attempts = 4;
        var pool = Executors.newFixedThreadPool(attempts);
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            int attempt = index;
            futures.add(pool.submit(() -> {
                start.await();
                // 直连接口没有聊天室房间行锁，控制器用 PlayerBetLock 在同一实例内按玩家串行。
                return playerBetLock.withPlayerLock(userId, () -> {
                    try {
                        gameService.placeBet(userId, new PlaceBetRequest(1, PlayType.FAN, List.of(1),
                                new BigDecimal("10.00"), "direct-concurrent-" + attempt));
                        accepted.incrementAndGet();
                    } catch (BetLimitExceededException expected) {
                        // 超出剩余额度的那一笔按设计被拒绝。
                    }
                    return null;
                });
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(accepted.get()).as("并发直连下注中通过限额的注单数").isEqualTo(2);
        assertQuotaNotBroken();
    }

    private void assertQuotaNotBroken() {
        BigDecimal used = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(stake), 0) FROM game_bet WHERE user_id = ? "
                        + "AND settlement_status <> 'CANCELED'", BigDecimal.class, accountId);
        assertThat(used).as("同一玩家当前期已占用额度").isLessThanOrEqualTo(new BigDecimal("25.00"));
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id = ? AND settlement_status = 'PENDING'",
                accountId)).as("并发下注中通过限额的注单数").isEqualTo(2);
        assertThat(balance()).isEqualByComparingTo("980.00");
    }

    @Test
    @Order(3)
    void batchBetOverLimitIsRejectedAtomicallyAndExplainsTheQuota() {
        walletService.grant(userId, userId, new BigDecimal("100.00"), "批量限额测试充值",
                "CHAT-FEEDBACK-GRANT-LIMIT-1");
        setFanLimit(15);

        ChatMessage message = messageService.sendUserMessage(userId, "main", "feedback-limit-1",
                "1番10,1番10", Instant.now());

        assertThat(message.messageType().name()).isEqualTo("USER_CHAT");
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id = ? AND settlement_status = 'PENDING'",
                accountId)).isZero();
        assertThat(balance()).isEqualByComparingTo("100.00");
        String feedback = "@" + DISPLAY_NAME + "  下注未成功"
                + "\n@" + DISPLAY_NAME + "  下注 1番10.00 已拒绝：超过番限额15，剩余可下5";
        assertThat(robotContents()).as("机器人消息内容").contains(feedback);
        assertThat(robotOutbox(feedback)).isEqualTo(1);
    }

    @Test
    @Order(4)
    void batchBetRejectedOnlyByQuotaKeepsInputWithoutBetOrDebit() {
        walletService.grant(userId, userId, new BigDecimal("100.00"), "全量限额测试充值",
                "CHAT-FEEDBACK-GRANT-LIMIT-2");
        setFanLimit(15);

        ChatMessage message = messageService.sendUserMessage(userId, "main", "feedback-limit-2",
                "1番100", Instant.now());

        assertThat(message.messageType().name()).isEqualTo("USER_CHAT");
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", accountId)).isZero();
        assertThat(balance()).isEqualByComparingTo("100.00");
        String feedback = "@" + DISPLAY_NAME + "  下注未成功"
                + "\n@" + DISPLAY_NAME + "  下注 1番100.00 已拒绝：超过番限额15，剩余可下15";
        assertThat(robotContents()).as("机器人消息内容").contains(feedback);
        assertThat(robotOutbox(feedback)).isEqualTo(1);
    }

    @Test
    @Order(5)
    void insufficientBetKeepsInputAndFeedbackButLeavesNoBetOrDebit() {
        ChatMessage message = messageService.sendUserMessage(userId, "main", "feedback-insufficient-1",
                "1番100", Instant.now());

        assertThat(message.content()).isEqualTo("1番100");
        assertThat(message.messageType().name()).isEqualTo("USER_CHAT");
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", accountId)).isZero();
        assertThat(balance()).isEqualByComparingTo("0.00");
        assertThat(robotContent("@" + DISPLAY_NAME + ", 积分不足，本次下注未提交!")).isEqualTo(1);
        assertThat(robotOutbox("@" + DISPLAY_NAME + ", 积分不足，本次下注未提交!")).isEqualTo(1);
    }

    @Test
    @Order(6)
    void acceptedBetDebitsWalletAndPersistsSuccessFeedback() {
        walletService.grant(userId, userId, new BigDecimal("100.00"), "聊天下注测试充值",
                "CHAT-FEEDBACK-GRANT-1");

        ChatMessage message = messageService.sendUserMessage(userId, "main", "feedback-success-1",
                "1番10", Instant.now());

        assertThat(message.messageType().name()).isEqualTo("USER_BET");
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id = ? AND settlement_status = 'PENDING'",
                accountId)).isEqualTo(1);
        assertThat(balance()).isEqualByComparingTo("90.00");
        assertThat(robotContents())
                .as("机器人消息内容")
                .contains("@" + DISPLAY_NAME + "，第3000000期攻击成功，使用积分10.00，剩余积分：90.00");
        assertThat(robotOutbox("@" + DISPLAY_NAME + "，第3000000期攻击成功，使用积分10.00，剩余积分：90.00"))
                .isEqualTo(1);
    }

    @Test
    @Order(7)
    void drawSettlesBetCreditsWalletAndPublishesSettlementFeedback() {
        walletService.grant(userId, userId, new BigDecimal("100.00"), "开奖结算测试充值",
                "CHAT-FEEDBACK-GRANT-SETTLEMENT");

        messageService.sendUserMessage(userId, "main", "feedback-settlement-1",
                "1番10", Instant.now());
        String issueNumber = jdbcTemplate.queryForObject(
                "SELECT issue_number FROM game_bet WHERE user_id=? ORDER BY id DESC LIMIT 1",
                String.class, accountId);
        jdbcTemplate.update("UPDATE game_issue SET status='SETTLED', phase='SETTLED' "
                + "WHERE game_code='AU8' AND phase IN ('BETTING', 'DRAWING') AND issue_number<>?", issueNumber);
        jdbcTemplate.update("UPDATE game_issue SET status='CLOSED', phase='DRAWING' "
                + "WHERE game_code='AU8' AND issue_number=?", issueNumber);
        long stableOperatorId = ensureDrawOperator();
        gameService.draw(stableOperatorId, List.of(1, 2, 3, 4, 5, 6, 7, 8));
        noticeDispatchService.dispatchDue(Instant.now().plusSeconds(5), 100);

        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id = ? AND settlement_status = 'WIN'",
                accountId)).isEqualTo(1);
        assertThat(balance()).isEqualByComparingTo("128.50");
        assertThat(robotContent("@" + DISPLAY_NAME + "，第" + issueNumber
                + "期中奖，返还积分38.50，结算后余额128.50积分。"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM demo_balance_ledger WHERE user_id = ? "
                + "AND operation_type = 'SETTLEMENT_CREDIT'", accountId)).isEqualTo(1);
    }

    private BigDecimal balance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", BigDecimal.class, accountId);
    }

    private long ensureDrawOperator() {
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT id FROM sys_user WHERE username = 'chat-feedback-draw-actor'", Long.class);
        long operatorId;
        if (userIds.isEmpty()) {
            operatorId = userRepository.insert("chat-feedback-draw-actor", "开奖集成测试操作者",
                    passwordPolicy.encode(PASSWORD), "ACTIVE");
            userRepository.assignRole(operatorId, "USER");
        } else {
            operatorId = userIds.get(0);
        }
        long accountId = walletService.ensureWalletForUser(operatorId, "开奖集成测试操作者");
        Integer machineCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent WHERE agent_code='CHAT-FEEDBACK-DRAW-MACHINE'", Integer.class);
        if (machineCount == null || machineCount == 0) {
            jdbcTemplate.update("""
                    INSERT INTO agent
                        (agent_code, display_name, account_user_id, system_owned, status,
                         odds_rate, special_odds_rate)
                    VALUES ('CHAT-FEEDBACK-DRAW-MACHINE', '开奖集成测试机器', ?, TRUE,
                            'DISABLED', 95, 18)
                    """, operatorId);
            long machineId = jdbcTemplate.queryForObject(
                    "SELECT id FROM agent WHERE agent_code='CHAT-FEEDBACK-DRAW-MACHINE'", Long.class);
            jdbcTemplate.update("INSERT INTO agent_game (agent_id, game_code) VALUES (?, 'AU8')", machineId);
            jdbcTemplate.update("UPDATE demo_user_account SET agent_id=? WHERE id=?", machineId, accountId);
        }
        return operatorId;
    }

    private int robotContent(String content) {
        return count("SELECT COUNT(*) FROM chat_message WHERE sender_type = 'ROBOT' AND content = ?", content);
    }

    private java.util.List<String> robotContents() {
        return jdbcTemplate.queryForList(
                "SELECT content FROM chat_message WHERE sender_type = 'ROBOT' ORDER BY id",
                String.class);
    }

    private int robotOutbox(String content) {
        return count("""
                SELECT COUNT(*) FROM chat_outbox o
                 JOIN chat_message m ON m.id = o.message_id
                WHERE m.sender_type = 'ROBOT' AND m.content = ?
                """, content);
    }

    private int count(String sql, Object... arguments) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return result == null ? 0 : result;
    }

    private void setFanLimit(int fanLimit) {
        jdbcTemplate.update("UPDATE agent SET fan_limit = ? WHERE id = ?", fanLimit, machineId);
    }

    private void configureMachine() {
        jdbcTemplate.update("""
                UPDATE agent
                   SET odds_rate = 95, special_odds_rate = 18, manual_betting_open = TRUE,
                       total_limit = 1000, positive_limit = 20000, angle_limit = 20000,
                       strict_limit = 20000, tong_limit = 20000, car_limit = 20000,
                       special_limit = 20000, odd_even_limit = 20000, big_small_limit = 20000,
                       fan_limit = 20000, add_limit = 20000, player_min_stake = 1, player_max_stake = 5001
                 WHERE id = ?
                """, machineId);
    }

    private void resetBettingConfig() {
        jdbcTemplate.update("""
                UPDATE game_betting_config
                   SET display_odds = 95, special_rebate = 1,
                       special_limit = 200, issue_total_limit = 5000, positive_limit = 20000,
                       angle_limit = 1000, strict_limit = 20000, tong_limit = 20000,
                       car_limit = 20000, odd_even_limit = 20000, big_small_limit = 20000,
                       fan_limit = 20000, add_limit = 20000, player_max_stake = 5001,
                       player_min_stake = 1
                 WHERE id = 1
                """);
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch WHERE message_id IN (SELECT id FROM chat_message "
                        + "WHERE sender_id IN (SELECT id FROM sys_user WHERE username = ?) "
                        + "OR issue_number IN (SELECT issue_number FROM game_issue_event "
                        + "WHERE game_code='AU8' AND issue_number LIKE '3000000%'))", USERNAME);
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch WHERE issue_number IN "
                + "(SELECT issue_number FROM game_issue WHERE draw_confirmed_by IN "
                + "(SELECT id FROM sys_user WHERE username = ?))", USERNAME);
        jdbcTemplate.update("DELETE FROM chat_outbox WHERE message_id IN "
                + "(SELECT id FROM chat_message WHERE sender_type = 'ROBOT' "
                + "AND content LIKE ?)", "@" + DISPLAY_NAME + "%");
        jdbcTemplate.update("DELETE FROM chat_message WHERE sender_type = 'ROBOT' AND content LIKE ?",
                "@" + DISPLAY_NAME + "%");
        jdbcTemplate.update("DELETE FROM chat_outbox WHERE message_id IN "
                + "(SELECT id FROM chat_message WHERE sender_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?))", USERNAME);
        jdbcTemplate.update("DELETE FROM chat_message WHERE sender_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM demo_balance_ledger WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?))", USERNAME);
        jdbcTemplate.update("DELETE FROM game_bet WHERE user_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?))", USERNAME);
        jdbcTemplate.update("DELETE FROM game_bet_batch WHERE account_id IN "
                + "(SELECT id FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?))", USERNAME);
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM agent WHERE agent_code = 'CHAT-FEEDBACK-MACHINE'");
        jdbcTemplate.update("DELETE FROM auth_ws_ticket WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM auth_session WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE operator_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", USERNAME);
    }
}
