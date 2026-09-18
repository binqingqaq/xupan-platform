package com.xupan.server.chat;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.chat.domain.ChatMessage;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.game.service.DemoGameService;
import com.xupan.server.game.service.VirtualWalletService;
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
import java.util.List;

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

    private long userId;
    private long accountId;

    @BeforeEach
    void setUp() {
        clean();
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
