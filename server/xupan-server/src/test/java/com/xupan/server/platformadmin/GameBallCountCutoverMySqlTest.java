package com.xupan.server.platformadmin;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PasswordPolicyService;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.platformadmin.service.GameSettingsService;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"xupan.automation.enabled=false", "spring.task.scheduling.enabled=false"})
@EnabledIfSystemProperty(named = "xupan.mysql.integration", matches = "true")
class GameBallCountCutoverMySqlTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private GameDataRepository gameData;
    @Autowired private GameSettingsService settings;
    @Autowired private UserRepository users;
    @Autowired private PasswordPolicyService passwords;
    @Autowired private PlatformTransactionManager transactionManager;

    private String gameCode;
    private String operatorUsername;
    private Long operatorId;
    private String startIssue;
    private String nextIssue;
    private String au8TestIssueNumber;
    private String au8TestRuleReason;
    private String legacyOrderCode;
    private String legacyIssueNumber;

    @AfterEach
    void clean() {
        if (gameCode != null) {
            jdbc.update("DELETE FROM game_ball_rule_change WHERE game_code = ?", gameCode);
            jdbc.update("DELETE FROM game_issue WHERE game_code = ?", gameCode);
            jdbc.update("DELETE FROM game_route_odds WHERE game_id = "
                    + "(SELECT id FROM game_definition WHERE game_code = ?)", gameCode);
            jdbc.update("DELETE FROM game_definition WHERE game_code = ?", gameCode);
        }
        if (au8TestIssueNumber != null) {
            jdbc.update("DELETE FROM game_issue WHERE game_code='AU8' AND issue_number=?", au8TestIssueNumber);
        }
        if (au8TestRuleReason != null) {
            jdbc.update("DELETE FROM game_ball_rule_change WHERE game_code='AU8' AND reason LIKE ?",
                    au8TestRuleReason + "%");
            jdbc.update("UPDATE game_definition SET next_issue_ball_count=8, ball_indexes='1,2,3,4,5,6,7,8' "
                    + ", updated_by=NULL WHERE game_code='AU8'");
        }
        if (legacyOrderCode != null) {
            jdbc.update("DELETE FROM center_order WHERE agent_order_no = ?", legacyOrderCode);
            jdbc.update("DELETE FROM center_agent WHERE agent_code = ?", legacyOrderCode);
            jdbc.update("DELETE FROM game_issue WHERE game_code='AU8' AND issue_number=?", legacyIssueNumber);
        }
        if (operatorUsername != null) {
            jdbc.update("DELETE FROM sys_operation_log WHERE operator_user_id = ?", operatorId);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", operatorId);
            jdbc.update("DELETE FROM sys_user WHERE id = ?", operatorId);
        }
    }

    @Test
    void cutoverRacingFinalizationUsesConsistentDefinitionThenIssueLockOrder() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        gameCode = "CUTOVER_" + suffix;
        operatorUsername = "cutover-" + suffix.toLowerCase();
        operatorId = users.insert(operatorUsername, "球数切换测试操作员",
                passwords.encode("CutoverTestPassword123"), "ACTIVE");
        users.assignRole(operatorId, "ADMIN");
        long gameId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM game_definition", Long.class);
        jdbc.update("""
                INSERT INTO game_definition
                    (id, game_code, display_name, ball_indexes, next_issue_ball_count,
                     sort_order, algorithm, switch_enabled, special_enabled, special_model,
                     keyboard_enabled, status, version, created_by, updated_by)
                VALUES (?, ?, '球数并发测试', '1,2,3,4,5,6,7,8', 8,
                        99, 'SUM', FALSE, TRUE, 'MODEL_ONE', TRUE, 'ACTIVE', 0, ?, ?)
                """, gameId, gameCode, operatorId, operatorId);
        startIssue = gameCode + "-3000000";
        nextIssue = gameCode + "-3000001";
        Instant startedAt = Instant.now().plusSeconds(3600);
        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, ball_count, opened_at,
                     issue_started_at, betting_ends_at, draw_ends_at)
                VALUES (?, ?, 'OPEN', 'DRAWING', 8, ?, ?, ?, ?)
                """, gameCode, startIssue, java.sql.Timestamp.from(startedAt), java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt.plusSeconds(180)),
                java.sql.Timestamp.from(startedAt.plusSeconds(300)));

        CountDownLatch finalizerLockedBoundary = new CountDownLatch(1);
        CountDownLatch allowFinalizerToFinish = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            var finalizer = executor.submit(() -> tx.execute(status -> {
                gameData.nextIssueBallCountForUpdate(gameCode);
                var issue = gameData.findCurrentIssueForUpdate(gameCode).orElseThrow();
                assertThat(issue.issueNumber()).isEqualTo(startIssue);
                finalizerLockedBoundary.countDown();
                await(allowFinalizerToFinish);
                assertThat(gameData.transitionPhase(gameCode, startIssue, "DRAWING", "SETTLED")).isTrue();
                gameData.saveBettingIssue(gameCode, nextIssue, startedAt.plusSeconds(300));
                return null;
            }));

            assertThat(finalizerLockedBoundary.await(5, TimeUnit.SECONDS)).isTrue();
            var cutover = executor.submit(() -> settings.update(gameId,
                    new GameSettingsService.GameInput(gameCode, "球数并发测试", "1,2,3,4,5,6,7",
                            null, 99, "SUM", null, false, true, "MODEL_ONE", true, "ACTIVE",
                            7, "双连接七球切换", BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO),
                    0, operatorId));
            Thread.sleep(150);
            assertThat(cutover.isDone()).as("settings must wait for finalizer's definition lock").isFalse();
            allowFinalizerToFinish.countDown();
            finalizer.get(10, TimeUnit.SECONDS);
            var changed = cutover.get(10, TimeUnit.SECONDS);
            assertThat(changed.nextIssueBallCount()).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT ball_count FROM game_issue WHERE game_code=? AND issue_number=?",
                    Integer.class, gameCode, startIssue)).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT ball_count FROM game_issue WHERE game_code=? AND issue_number=?",
                    Integer.class, gameCode, nextIssue)).isEqualTo(8);
            assertThat(gameData.firstSevenBallIssueNumber(gameCode)).isEmpty();

            String firstSevenBettingIssue = gameCode + "-3000002";
            gameData.saveBettingIssue(gameCode, firstSevenBettingIssue, startedAt.plusSeconds(600));
            assertThat(jdbc.queryForObject("SELECT ball_count FROM game_issue WHERE game_code=? AND issue_number=?",
                    Integer.class, gameCode, firstSevenBettingIssue)).isEqualTo(7);
            assertThat(gameData.firstSevenBallIssueNumber(gameCode)).contains(firstSevenBettingIssue);
        } finally {
            allowFinalizerToFinish.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void au8DatabaseRuleRejectsRevertingToEightAfterSevenBallCutover() {
        operatorUsername = "cutover-au8-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        operatorId = users.insert(operatorUsername, "AU8切换测试操作员",
                passwords.encode("CutoverTestPassword123"), "ACTIVE");
        users.assignRole(operatorId, "ADMIN");
        GameSettingsService.GameRow current = settings.get(1, operatorId);
        String reason = "AU8-RULE-REVERT-TEST-" + UUID.randomUUID().toString().replace("-", "");
        au8TestRuleReason = reason;
        GameSettingsService.GameRow armed = settings.update(1, gameInput(current, 7, reason + "-ARM"),
                current.version(), operatorId);
        assertThat(gameData.firstSevenBallIssueNumber("AU8")).isEmpty();

        GameSettingsService.GameRow revertedBeforeStart = settings.update(1,
                gameInput(armed, 8, reason + "-REVERT-BEFORE-START"), armed.version(), operatorId);
        assertThat(revertedBeforeStart.nextIssueBallCount()).isEqualTo(8);
        assertThat(gameData.ballCountForIssue("AU8", "3000000")).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_ball_rule_change "
                        + "WHERE game_code='AU8' AND reason LIKE ? AND from_ball_count=7 AND to_ball_count=8",
                Integer.class, reason + "%")).isEqualTo(1);

        GameSettingsService.GameRow rearmed = settings.update(1,
                gameInput(revertedBeforeStart, 7, reason + "-ARM-ACTIVE"), revertedBeforeStart.version(), operatorId);
        au8TestIssueNumber = "3000000";
        gameData.saveBettingIssue("AU8", au8TestIssueNumber, Instant.now());
        assertThat(jdbc.queryForObject("SELECT ball_count FROM game_issue WHERE game_code='AU8' "
                + "AND issue_number=?", Integer.class, au8TestIssueNumber)).isEqualTo(7);
        assertThat(gameData.firstSevenBallIssueNumber("AU8")).contains(au8TestIssueNumber);

        assertThatThrownBy(() -> settings.update(1,
                gameInput(rearmed, 8, reason + "-REVERT-AFTER-START"), rearmed.version(), operatorId))
                .isInstanceOf(BusinessException.class)
                .hasMessage("GAME_BALL_COUNT_REVERT_FORBIDDEN");
        assertThatThrownBy(() -> jdbc.update("UPDATE game_definition SET next_issue_ball_count=8 "
                + "WHERE game_code='AU8'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("AU8 ball count cannot revert to eight");
        assertThat(jdbc.queryForObject("SELECT next_issue_ball_count FROM game_definition "
                + "WHERE game_code='AU8'", Integer.class)).isEqualTo(7);
    }

    private static GameSettingsService.GameInput gameInput(GameSettingsService.GameRow row, int ballCount,
                                                           String reason) {
        return new GameSettingsService.GameInput(row.gameCode(), row.displayName(),
                ballCount == 7 ? "1,2,3,4,5,6,7" : "1,2,3,4,5,6,7,8", row.drawSourceUrl(),
                row.sortOrder(), row.algorithm(), row.playPrefix(), row.switchEnabled(), row.specialEnabled(),
                row.specialModel(), row.keyboardEnabled(), row.status(), ballCount, reason,
                row.oddsAte(), row.oddsAdx(), row.oddsBte(), row.oddsBdx(), row.oddsCte(), row.oddsCdx(),
                row.oddsDte(), row.oddsDdx());
    }

    @Test
    @Transactional
    void mysqlGuardsRejectOrphanAndEighthBallBetsAndProtectConfirmedDrawDeletion() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String sevenIssue = "399995" + suffix.substring(0, 3);
        String missingIssue = "399994" + suffix.substring(0, 3);
        Long accountId = jdbc.queryForObject(
                "SELECT id FROM demo_user_account WHERE user_code='DEMO-USER'", Long.class);
        jdbc.update("""
                INSERT INTO game_issue (game_code, issue_number, status, phase, ball_count)
                VALUES ('AU8', ?, 'CLOSED', 'SETTLED', 7)
                """, sevenIssue);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number,
                     ball_number, play_type, parameters_text, stake, odds_snapshot)
                VALUES ('AU8', ?, ?, ?, ?, 1, 'FAN', '1', 10.00, 3.850)
                """, accountId, "BET-ORPHAN-" + suffix, "ORPHAN-" + suffix, missingIssue))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Bet issue does not exist");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO game_bet
                    (game_code, user_id, bet_code, request_idempotency_key, issue_number,
                     ball_number, play_type, parameters_text, stake, odds_snapshot)
                VALUES ('AU8', ?, ?, ?, ?, 8, 'FAN', '1', 10.00, 3.850)
                """, accountId, "BET-EIGHTH-" + suffix, "EIGHTH-" + suffix, sevenIssue))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Bet ball number exceeds issue ball count");

        String confirmedIssue = "399993" + suffix.substring(0, 3);
        jdbc.update("""
                INSERT INTO game_issue
                    (game_code, issue_number, status, phase, ball_count,
                     number_1, number_2, number_3, number_4, number_5, number_6, number_7, number_8,
                     draw_result_version, draw_confirmed_at)
                VALUES ('AU8', ?, 'CLOSED', 'SETTLED', 8,
                        1, 2, 3, 4, 5, 6, 7, 8, 1, CURRENT_TIMESTAMP)
                """, confirmedIssue);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM game_issue WHERE game_code='AU8' AND issue_number=?",
                confirmedIssue))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Confirmed draw result cannot be deleted");
    }

    @Test
    void newAcceptedCenterOrderRequiresBallNumberAndExistingIssue() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        legacyOrderCode = "NEW_NULL_BALL_" + suffix;
        legacyIssueNumber = "399996" + suffix.substring(0, 3);
        jdbc.update("""
                INSERT INTO center_agent
                    (agent_code, agent_name, upstream_account, token_hash, token_prefix, status)
                VALUES (?, '空球位约束测试代理', ?, ?, 'test', 'ACTIVE')
                """, legacyOrderCode, "up_" + suffix, "a".repeat(64));
        long centerId = jdbc.queryForObject("SELECT id FROM center_agent WHERE agent_code=?",
                Long.class, legacyOrderCode);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO center_order
                    (center_agent_id, agent_order_no, game_code, issue_number, play_type, play_text,
                     odds, amount, status, frozen_amount, accepted_at)
                VALUES (?, ?, 'AU8', ?, 'FAN', 'legacy-null-ball', 3.8500, 10.00,
                        'ACCEPTED', 10.00, CURRENT_TIMESTAMP)
                """, centerId, legacyOrderCode, legacyIssueNumber))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Accepted center order requires a ball number");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO center_order
                    (center_agent_id, agent_order_no, game_code, issue_number, ball_number,
                     play_type, play_text, odds, amount, status, frozen_amount, accepted_at)
                VALUES (?, ?, 'AU8', ?, 1, 'FAN', 'missing-issue', 3.8500, 10.00,
                        'ACCEPTED', 10.00, CURRENT_TIMESTAMP)
                """, centerId, legacyOrderCode + "_MISSING", legacyIssueNumber))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Center order issue does not exist");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("cutover test latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cutover test interrupted", interrupted);
        }
    }
}
