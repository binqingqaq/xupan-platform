package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PlatformBettingPlayStatsServiceTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PlatformBettingPlayStatsService service;

    @Test
    @Transactional
    void aggregatesNormalPlayerOrdersAcrossMachinesExcludingBotsAndUsingDeletedPlayerSnapshots() {
        long admin = admin();
        long machineOne = machine("stats-machine-one");
        long machineTwo = machine("stats-machine-two");
        long normalAccount = account("stats-normal", machineOne, "NORMAL");
        long botAccount = account("stats-bot", machineTwo, "BOT");
        String issue = issue(Instant.now().plusSeconds(600));

        bet(issue, normalAccount, "FAN", 7, "10.00", "PENDING", null, "NORMAL");
        bet(issue, normalAccount, "FAN", 7, "5.00", "PENDING", null, "NORMAL");
        betWithParameters(issue, normalAccount, "POSITIVE", "1", 7, "11.00", "PENDING", null, "NORMAL");
        betWithParameters(issue, normalAccount, "POSITIVE", "2", 7, "13.00", "PENDING", null, "NORMAL");
        betWithParameters(issue, normalAccount, "NONE", "1,3", 7, "17.00", "PENDING", null, "NORMAL");
        bet(issue, botAccount, "FAN", 7, "20.00", "PENDING", null, "BOT");
        bet(issue, botAccount, "ODD_EVEN", 7, "7.00", "PENDING", null, "BOT");
        bet(issue, normalAccount, "SPECIAL", 7, "999.00", "CANCELED", null, "NORMAL");
        bet(issue, null, "FAN", 7, "1.00", "PENDING", machineOne, "NORMAL");
        bet(issue, null, "FAN", 7, "50.00", "PENDING", machineTwo, "BOT");

        PlatformBettingPlayStatsService.CurrentStats stats = service.current("AU8", false, admin);

        assertThat(stats.issueNumber()).isEqualTo(issue);
        assertThat(stats.phase()).isEqualTo("BETTING");
        assertThat(stats.ballCount()).isEqualTo(7);
        PlatformBettingPlayStatsService.BallStats ball = stats.balls().get(6);
        assertThat(ball.ballNumber()).isEqualTo(7);
        assertThat(ball.orderCount()).isEqualTo(6);
        assertThat(ball.totalStake()).isEqualByComparingTo("57.00");
        assertThat(ball.plays()).extracting(PlatformBettingPlayStatsService.PlayStats::playType)
                .containsExactly("FAN", "POSITIVE", "POSITIVE");
        assertThat(ball.plays()).extracting(PlatformBettingPlayStatsService.PlayStats::playName)
                .containsExactly("1番", "1正", "2正");
        assertThat(ball.plays().get(1).orderCount()).isEqualTo(2);
        assertThat(ball.plays().get(1).totalStake()).isEqualByComparingTo("28.00");
        assertThat(ball.plays().get(2).orderCount()).isEqualTo(1);
        assertThat(ball.plays().get(2).totalStake()).isEqualByComparingTo("13.00");

        PlatformBettingPlayStatsService.CurrentStats includingBots = service.current("AU8", true, admin);
        PlatformBettingPlayStatsService.BallStats allMachineBall = includingBots.balls().get(6);
        assertThat(allMachineBall.orderCount()).isEqualTo(9);
        assertThat(allMachineBall.totalStake()).isEqualByComparingTo("134.00");
        assertThat(allMachineBall.plays()).extracting(PlatformBettingPlayStatsService.PlayStats::playType)
                .containsExactly("FAN", "POSITIVE", "POSITIVE", "ODD_EVEN");
        assertThat(allMachineBall.plays().get(0).orderCount()).isEqualTo(5);
        assertThat(allMachineBall.plays().get(0).totalStake()).isEqualByComparingTo("86.00");
        assertThat(allMachineBall.plays()).extracting(PlatformBettingPlayStatsService.PlayStats::playName)
                .contains("1正", "2正", "单双");
    }

    @Test
    @Transactional
    void returnsStableEmptyStateWhenTheGameHasNoActiveIssue() {
        long admin = admin();
        String gameCode = "PS" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        Long nextId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM game_definition", Long.class);
        jdbc.update("""
                INSERT INTO game_definition (id, game_code, display_name, ball_indexes,
                                             next_issue_ball_count, status)
                VALUES (?, ?, '玩法统计测试', '1,2,3,4,5,6,7', 7, 'ACTIVE')
                """, nextId, gameCode);

        PlatformBettingPlayStatsService.CurrentStats stats = service.current(gameCode, false, admin);

        assertThat(stats.gameCode()).isEqualTo(gameCode);
        assertThat(stats.gameName()).isEqualTo("玩法统计测试");
        assertThat(stats.issueNumber()).isNull();
        assertThat(stats.phase()).isEqualTo("NONE");
        assertThat(stats.ballCount()).isEqualTo(7);
        assertThat(stats.balls()).isEmpty();
    }

    @Test
    @Transactional
    void savesAndClearsOnePresetWithAuditAndRejectsStaleIssue() {
        long admin = admin();
        String issue = issue(Instant.now().plusSeconds(600));

        PlatformBettingPlayStatsService.PresetSummary saved = service.preset("AU8", issue, 7, 8, admin);

        assertThat(saved.presetNumber()).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT number_7 FROM game_issue WHERE game_code='AU8' AND issue_number=?",
                Integer.class, issue)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT number_1 FROM game_issue WHERE game_code='AU8' AND issue_number=?",
                Integer.class, issue)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE permission_code = ?",
                Integer.class, "BETTING_DRAW_PRESET_MANAGE")).isEqualTo(1);

        PlatformBettingPlayStatsService.PresetSummary cleared = service.preset("AU8", issue, 7, null, admin);
        assertThat(cleared.presetNumber()).isNull();
        assertThat(jdbc.queryForObject("SELECT number_7 FROM game_issue WHERE game_code='AU8' AND issue_number=?",
                Integer.class, issue)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE permission_code = ?",
                Integer.class, "BETTING_DRAW_PRESET_MANAGE")).isEqualTo(2);

        assertThatThrownBy(() -> service.preset("AU8", "399999999", 7, 3, admin))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.status().value()).isEqualTo(409);
                    assertThat(business.code()).isEqualTo("BETTING_DRAW_PRESET_STALE_ISSUE");
                });
    }

    @Test
    @Transactional
    void rejectsPresetWithinTheFinalTenSecondsAndRequiresSeparatePresetPermission() {
        long admin = admin();
        String issue = issue(Instant.now().plusSeconds(8));
        long subAccount = users.insert("stats-sub-" + suffix(), "统计子账号", "test-hash", "ACTIVE");
        users.assignRole(subAccount, "SUB_ACCOUNT");

        assertThat(service.current("AU8", false, subAccount).phase()).isEqualTo("BETTING");

        assertThatThrownBy(() -> service.preset("AU8", issue, 1, 2, admin))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code())
                        .isEqualTo("BETTING_DRAW_PRESET_CLOSED"));
        assertThatThrownBy(() -> service.preset("AU8", issue, 1, 2, subAccount))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).status().value()).isEqualTo(403));
    }

    private long admin() {
        long admin = users.insert("stats-admin-" + suffix(), "玩法统计管理员", "test-hash", "ACTIVE");
        users.assignRole(admin, "ADMIN");
        return admin;
    }

    private long machine(String prefix) {
        long machineUser = users.insert(prefix + "-user-" + suffix(), "统计机器", "test-hash", "ACTIVE");
        users.assignRole(machineUser, "AGENT");
        jdbc.update("""
                INSERT INTO agent (agent_code, display_name, account_user_id, system_owned, status)
                VALUES (?, '统计机器', ?, FALSE, 'ACTIVE')
                """, prefix.toUpperCase() + "-" + suffix(), machineUser);
        return jdbc.queryForObject("SELECT id FROM agent WHERE account_user_id = ?", Long.class, machineUser);
    }

    private long account(String prefix, long agentId, String playerKind) {
        String userCode = prefix + "-" + suffix();
        jdbc.update("""
                INSERT INTO demo_user_account (user_code, member_code, display_name, player_kind, status, agent_id)
                VALUES (?, ?, '统计测试玩家', ?, 'ACTIVE', ?)
                """, userCode, "M" + suffix(), playerKind, agentId);
        return jdbc.queryForObject("SELECT id FROM demo_user_account WHERE user_code = ?", Long.class, userCode);
    }

    private String issue(Instant drawEndsAt) {
        String issue = "39" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        Instant startedAt = drawEndsAt.minusSeconds(300);
        jdbc.update("""
                INSERT INTO game_issue (game_code, issue_number, status, phase, ball_count,
                                        issue_started_at, betting_ends_at, draw_ends_at)
                VALUES ('AU8', ?, 'OPEN', 'BETTING', 7, ?, ?, ?)
                """, issue, Timestamp.from(startedAt), Timestamp.from(startedAt.plusSeconds(180)),
                Timestamp.from(drawEndsAt));
        return issue;
    }

    private void bet(String issue, Long accountId, String playType, int ballNumber, String stake,
                     String settlementStatus, Long agentIdSnapshot, String playerKindSnapshot) {
        betWithParameters(issue, accountId, playType, "1", ballNumber, stake,
                settlementStatus, agentIdSnapshot, playerKindSnapshot);
    }

    private void betWithParameters(String issue, Long accountId, String playType, String parametersText,
                                   int ballNumber, String stake, String settlementStatus,
                                   Long agentIdSnapshot, String playerKindSnapshot) {
        jdbc.update("""
                INSERT INTO game_bet (user_id, bet_code, issue_number, game_code, ball_number,
                                      play_type, parameters_text, stake, odds_snapshot, settlement_status,
                                      agent_id_snapshot, player_kind_snapshot)
                VALUES (?, ?, ?, 'AU8', ?, ?, ?, ?, 3.850, ?, ?, ?)
                """, accountId, "STATS-BET-" + suffix(), issue, ballNumber, playType, parametersText,
                new java.math.BigDecimal(stake), settlementStatus, agentIdSnapshot, playerKindSnapshot);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
