package com.xupan.server.game.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.web.PlaceBetRequest;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"xupan.automation.enabled=false", "spring.task.scheduling.enabled=false"})
class ManualDrawBetRaceIntegrationTest {

    private static final String USERNAME = "manual-draw-race-player";
    private static final String PASSWORD = "ManualDrawRace12345";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordPolicyService passwords;
    @Autowired
    private VirtualWalletService wallets;
    @Autowired
    private DemoGameService game;
    @MockitoSpyBean
    private GameDataRepository repository;

    private long userId;

    @BeforeEach
    void setUp() {
        clean();
        userId = users.insert(USERNAME, "手动开奖并发测试玩家", passwords.encode(PASSWORD), "ACTIVE");
        users.assignRole(userId, "USER");
        long accountId = wallets.ensureWalletForUser(userId, "手动开奖并发测试玩家");
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, account_user_id, system_owned, status, bot_count,
                                   odds_rate, special_odds_rate, manual_betting_open, rebate_rate, special_rebate_rate,
                                   rebate_mode, total_limit, fan_limit, player_min_stake, player_max_stake)
                VALUES ('MANUAL-DRAW-RACE', '手动开奖并发测试机器', ?, FALSE, 'ACTIVE', 0,
                        95, 18, TRUE, 5, 1, 'SINGLE', 10000, 10000, 1, 5000)
                """, userId);
        long machineId = jdbc.queryForObject("SELECT id FROM agent WHERE agent_code='MANUAL-DRAW-RACE'", Long.class);
        jdbc.update("UPDATE demo_user_account SET agent_id=? WHERE id=?", machineId, accountId);
        wallets.grant(userId, userId, new BigDecimal("100.00"), "并发测试积分", "MANUAL-DRAW-RACE-GRANT");
        jdbc.update("DELETE FROM game_bet");
        jdbc.update("DELETE FROM game_bet_batch");
        jdbc.update("DELETE FROM game_issue_event");
        jdbc.update("DELETE FROM game_issue");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void manualDrawLocksIssueBeforeReadingBetsSoAcceptedBetCannotBeMissed() throws Exception {
        game.placeBetForUser(userId, new PlaceBetRequest(1, PlayType.FAN, List.of(3),
                new BigDecimal("10.00"), "manual-draw-seed"));
        CountDownLatch betListRead = new CountDownLatch(1);
        CountDownLatch continueDraw = new CountDownLatch(1);
        CountDownLatch betStarted = new CountDownLatch(1);
        AtomicBoolean pauseNextRead = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (pauseNextRead.compareAndSet(true, false)) {
                betListRead.countDown();
                if (!continueDraw.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发测试等待开奖继续超时");
                }
            }
            return result;
        }).when(repository).findBetsByIssue(anyString());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var draw = executor.submit(() -> game.draw(userId, List.of(1, 2, 4, 5, 6, 7, 8, 9)));
            assertThat(betListRead.await(10, TimeUnit.SECONDS)).isTrue();
            var concurrentBet = executor.submit(() -> {
                betStarted.countDown();
                return game.placeBetForUser(userId, new PlaceBetRequest(1, PlayType.FAN, List.of(3),
                        new BigDecimal("10.00"), "manual-draw-racing-bet"));
            });
            assertThat(betStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> concurrentBet.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            continueDraw.countDown();
            draw.get(20, TimeUnit.SECONDS);
            assertThatThrownBy(() -> concurrentBet.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class);
        } finally {
            continueDraw.countDown();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_bet WHERE user_id = ?", Long.class,
                wallets.getForCurrentUser(userId).accountId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT b.settlement_status FROM game_bet b
                JOIN game_bet_batch batch ON batch.id = b.batch_id
                WHERE batch.idempotency_key = ?
                """, String.class, "SINGLE-manual-draw-seed")).isEqualTo("LOSE");
        assertThat(jdbc.queryForObject("SELECT balance FROM demo_user_account WHERE sys_user_id = ?",
                BigDecimal.class, userId)).isEqualByComparingTo("90.00");
    }

    private void clean() {
        jdbc.update("DELETE FROM auth_ws_ticket");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM sys_operation_log");
        jdbc.update("DELETE FROM demo_balance_ledger");
        jdbc.update("DELETE FROM game_bet");
        jdbc.update("DELETE FROM game_bet_batch");
        jdbc.update("DELETE FROM game_issue_event");
        jdbc.update("DELETE FROM game_issue");
        jdbc.update("DELETE FROM demo_user_account WHERE sys_user_id IN (SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbc.update("DELETE FROM agent WHERE agent_code = 'MANUAL-DRAW-RACE'");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = ?)", USERNAME);
        jdbc.update("DELETE FROM sys_user WHERE username = ?", USERNAME);
    }
}
