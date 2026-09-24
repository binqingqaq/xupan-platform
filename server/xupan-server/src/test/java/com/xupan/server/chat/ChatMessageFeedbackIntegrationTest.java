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
import com.xupan.server.game.web.PlaceBetRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private long userId;
    private long accountId;

    @BeforeEach
    void setUp() {
        clean();
        resetBettingConfig();
        userId = userRepository.insert(USERNAME, DISPLAY_NAME,
                passwordPolicy.encode(PASSWORD), "ACTIVE");
        userRepository.assignRole(userId, "USER");
        accountId = walletService.ensureWalletForUser(userId, DISPLAY_NAME);
        jdbcTemplate.update("DELETE FROM chat_robot_dispatch");
        jdbcTemplate.update("DELETE FROM game_issue_event");
        jdbcTemplate.update("DELETE FROM game_issue");
    }

    @AfterEach
    void tearDown() {
        clean();
        resetBettingConfig();
    }

    @Test
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
    void batchBetDropsOnlyTheOverLimitItemAndExplainsTheQuota() {
        walletService.grant(userId, userId, new BigDecimal("100.00"), "批量限额测试充值",
                "CHAT-FEEDBACK-GRANT-LIMIT-1");
        setFanLimit(15);

        ChatMessage message = messageService.sendUserMessage(userId, "main", "feedback-limit-1",
                "1番10,2番10", Instant.now());

        assertThat(message.messageType().name()).isEqualTo("USER_BET");
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id = ? AND settlement_status = 'PENDING'",
                accountId)).isEqualTo(1);
        assertThat(balance()).isEqualByComparingTo("90.00");
        String feedback = "@" + DISPLAY_NAME + "  攻击成功，使用粮草10, 剩余粮草：90"
                + "\n@" + DISPLAY_NAME + "  下注 2番10 已拒绝：超过番限额15，剩余可下5";
        assertThat(robotContents()).as("机器人消息内容").contains(feedback);
        assertThat(robotOutbox(feedback)).isEqualTo(1);
    }

    @Test
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
                + "\n@" + DISPLAY_NAME + "  下注 1番100 已拒绝：超过番限额15，剩余可下15";
        assertThat(robotContents()).as("机器人消息内容").contains(feedback);
        assertThat(robotOutbox(feedback)).isEqualTo(1);
    }

    @Test
    void insufficientBetKeepsInputAndFeedbackButLeavesNoBetOrDebit() {
        ChatMessage message = messageService.sendUserMessage(userId, "main", "feedback-insufficient-1",
                "1番100", Instant.now());

        assertThat(message.content()).isEqualTo("1番100");
        assertThat(message.messageType().name()).isEqualTo("USER_CHAT");
        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", accountId)).isZero();
        assertThat(balance()).isEqualByComparingTo("0.00");
        assertThat(robotContent("@" + DISPLAY_NAME + ", 余额不足!")).isEqualTo(1);
        assertThat(robotOutbox("@" + DISPLAY_NAME + ", 余额不足!")).isEqualTo(1);
    }

    @Test
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
                .contains("@" + DISPLAY_NAME + "  攻击成功，使用粮草10, 剩余粮草：90");
        assertThat(robotOutbox("@" + DISPLAY_NAME + "  攻击成功，使用粮草10, 剩余粮草：90"))
                .isEqualTo(1);
    }

    @Test
    void drawSettlesBetCreditsWalletAndPublishesSettlementFeedback() {
        walletService.grant(userId, userId, new BigDecimal("100.00"), "开奖结算测试充值",
                "CHAT-FEEDBACK-GRANT-SETTLEMENT");

        messageService.sendUserMessage(userId, "main", "feedback-settlement-1",
                "1番10", Instant.now());
        gameService.draw(userId, List.of(1, 2, 3, 4, 5, 6, 7, 8));

        assertThat(count("SELECT COUNT(*) FROM game_bet WHERE user_id = ? AND settlement_status = 'WIN'",
                accountId)).isEqualTo(1);
        assertThat(balance()).isEqualByComparingTo("128.50");
        assertThat(robotContent("@" + DISPLAY_NAME + ", 第3000000期中奖，返还粮草38.5, 当前粮草：128.5"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM demo_balance_ledger WHERE user_id = ? "
                + "AND operation_type = 'SETTLEMENT_CREDIT'", accountId)).isEqualTo(1);
    }

    private BigDecimal balance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM demo_user_account WHERE id = ?", BigDecimal.class, accountId);
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
        jdbcTemplate.update("UPDATE game_betting_config SET fan_limit = ? WHERE id = 1", fanLimit);
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
        jdbcTemplate.update("DELETE FROM demo_user_account WHERE sys_user_id IN "
                + "(SELECT id FROM sys_user WHERE username = ?)", USERNAME);
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
