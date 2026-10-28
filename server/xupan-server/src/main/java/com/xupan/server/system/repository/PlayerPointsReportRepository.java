package com.xupan.server.system.repository;

import com.xupan.server.system.service.PlayerDeskScopeService.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Repository
public class PlayerPointsReportRepository {

    private final JdbcTemplate jdbc;

    public PlayerPointsReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PlayerAccount> findPlayers(String playerKind, Instant fromInclusive, Instant toExclusive,
                                           Scope scope) {
        List<Object> args = new ArrayList<>(List.of(timestamp(fromInclusive), timestamp(toExclusive), playerKind));
        args.addAll(scope.agentIdParameters());
        return jdbc.query("""
                SELECT u.id AS user_id, a.id AS account_id, a.agent_id, a.member_code, a.user_code,
                       COALESCE(g.rebate_mode, 'SINGLE') AS rebate_mode,
                       u.display_name, a.player_kind,
                       COALESCE((
                           SELECT l.balance_after
                             FROM demo_balance_ledger l
                            WHERE l.user_id = a.id AND l.created_at < ?
                            ORDER BY l.created_at DESC, l.id DESC
                            LIMIT 1
                       ), 0.00) AS opening_balance,
                       COALESCE((
                           SELECT l.balance_after
                             FROM demo_balance_ledger l
                            WHERE l.user_id = a.id AND l.created_at < ?
                            ORDER BY l.created_at DESC, l.id DESC
                            LIMIT 1
                       ), 0.00) AS closing_balance
                  FROM sys_user u
                  JOIN demo_user_account a ON a.sys_user_id = u.id
                  LEFT JOIN agent g ON g.id = a.agent_id
                 WHERE a.identity_type IN ('REAL', 'TEST')
                   AND a.player_kind = ?
                   AND u.status <> 'DELETED'
                   AND a.status <> 'DELETED'
                """ + scope.agentIdCondition() + " ORDER BY a.id",
                (rs, rowNum) -> new PlayerAccount(
                rs.getLong("user_id"), rs.getLong("account_id"), rs.getLong("agent_id"),
                rs.getString("rebate_mode"), rs.getString("member_code"),
                rs.getString("user_code"), rs.getString("display_name"), rs.getString("player_kind"),
                money(rs.getBigDecimal("opening_balance")), money(rs.getBigDecimal("closing_balance"))),
                args.toArray());
    }

    public String findAgentRebateMode(long agentId) {
        return jdbc.queryForObject("SELECT COALESCE(rebate_mode, 'SINGLE') FROM agent WHERE id = ?",
                String.class, agentId);
    }

    public List<BetEvent> findBetEvents(String playerKind, Instant fromInclusive, Instant toExclusive,
                                        int perPlayerLimit, Scope scope) {
        // 参数顺序必须与 SQL 里的占位符顺序一致：player_kind → 日期窗口 → 范围(agent_id) → row_no。
        // 旧写法把 perPlayerLimit 放在范围参数**之前**，GLOBAL 范围（无 agent_id 占位符）时看不出问题，
        // 但 MACHINE/GROUP 范围下会把 perPlayerLimit 绑到 agent_id、把 agentId 绑到 row_no，报表直接查空。
        List<Object> args = new ArrayList<>(List.of(playerKind, timestamp(fromInclusive), timestamp(toExclusive)));
        args.addAll(scope.agentIdParameters());
        args.add(perPlayerLimit);
        return jdbc.query("""
                SELECT x.id, x.account_id, x.bet_code, x.issue_number, x.ball_number, x.play_type,
                       x.parameters_text, x.stake, x.odds_snapshot, x.rebate_rate_snapshot,
                       x.special_rebate_rate_snapshot, x.selection_unit_stake, x.settlement_status,
                       x.net_profit, x.explanation, x.created_at, x.settled_at
                  FROM (
                        SELECT b.id, b.user_id AS account_id, b.bet_code, b.issue_number, b.ball_number,
                               b.play_type, b.parameters_text, b.stake, b.odds_snapshot,
                               b.rebate_rate_snapshot, b.special_rebate_rate_snapshot,
                               (SELECT MIN(s.unit_stake) FROM game_bet_selection s WHERE s.bet_id = b.id)
                                   AS selection_unit_stake,
                               b.settlement_status, b.net_profit, b.explanation,
                               b.created_at, b.settled_at,
                               ROW_NUMBER() OVER (
                                   PARTITION BY b.user_id ORDER BY b.created_at DESC, b.id DESC
                               ) AS row_no
                          FROM game_bet b
                          JOIN demo_user_account a ON a.id = b.user_id
                          JOIN sys_user u ON u.id = a.sys_user_id
                         WHERE a.identity_type IN ('REAL', 'TEST')
                           AND a.player_kind = ?
                           AND u.status <> 'DELETED'
                           AND a.status <> 'DELETED'
                           AND b.settlement_status <> 'CANCELED'
                           AND b.created_at >= ? AND b.created_at < ?
                """ + scope.agentIdCondition() + """
                  ) x
                 WHERE x.row_no <= ?
                 ORDER BY x.account_id, x.created_at DESC, x.id DESC
                """, (rs, rowNum) -> new BetEvent(
                rs.getLong("id"), rs.getLong("account_id"), rs.getString("bet_code"),
                rs.getString("issue_number"), rs.getInt("ball_number"), rs.getString("play_type"),
                rs.getString("parameters_text"), money(rs.getBigDecimal("stake")),
                money(rs.getBigDecimal("odds_snapshot")), money(rs.getBigDecimal("rebate_rate_snapshot")),
                money(rs.getBigDecimal("special_rebate_rate_snapshot")),
                nullableMoney(rs.getBigDecimal("selection_unit_stake")), rs.getString("settlement_status"),
                money(rs.getBigDecimal("net_profit")), rs.getString("explanation"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("settled_at"))),
                args.toArray());
    }

    public void forEachBetEvent(String playerKind, Instant fromInclusive, Instant toExclusive,
                                Scope scope, Consumer<BetEvent> consumer) {
        List<Object> args = new ArrayList<>(List.of(playerKind, timestamp(fromInclusive), timestamp(toExclusive)));
        args.addAll(scope.agentIdParameters());
        jdbc.query("""
                SELECT b.id, b.user_id AS account_id, b.bet_code, b.issue_number, b.ball_number,
                       b.play_type, b.parameters_text, b.stake, b.odds_snapshot,
                       b.rebate_rate_snapshot, b.special_rebate_rate_snapshot,
                       (SELECT MIN(s.unit_stake) FROM game_bet_selection s WHERE s.bet_id = b.id)
                           AS selection_unit_stake,
                       b.settlement_status, b.net_profit, b.explanation, b.created_at, b.settled_at
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                  JOIN sys_user u ON u.id = a.sys_user_id
                 WHERE a.identity_type IN ('REAL', 'TEST')
                   AND a.player_kind = ?
                   AND u.status <> 'DELETED'
                   AND a.status <> 'DELETED'
                   AND b.settlement_status <> 'CANCELED'
                   AND b.created_at >= ? AND b.created_at < ?
                """ + scope.agentIdCondition() + " ORDER BY b.user_id, b.created_at, b.id",
                (RowCallbackHandler) rs -> consumer.accept(mapBetEvent(rs)), args.toArray());
    }

    public List<LedgerEvent> findLedgerEvents(String playerKind, Instant fromInclusive, Instant toExclusive,
                                               int perPlayerLimit, Scope scope) {
        // 同上：范围参数必须排在 row_no 之前，否则非 GLOBAL 范围下参数错位。
        List<Object> args = new ArrayList<>(List.of(playerKind, timestamp(fromInclusive), timestamp(toExclusive)));
        args.addAll(scope.agentIdParameters());
        args.add(perPlayerLimit);
        return jdbc.query("""
                SELECT x.id, x.account_id, x.operation_type, x.amount, x.balance_before,
                       x.balance_after, x.operator_user_id, x.operator_name, x.idempotency_key,
                       x.related_bet_id, x.issue_number, x.reason, x.created_at
                  FROM (
                        SELECT l.id, l.user_id AS account_id, l.operation_type, l.amount,
                               l.balance_before, l.balance_after, l.operator_user_id,
                               l.operator_name, l.idempotency_key, l.related_bet_id,
                               l.issue_number, l.reason, l.created_at,
                               ROW_NUMBER() OVER (
                                   PARTITION BY l.user_id ORDER BY l.created_at DESC, l.id DESC
                               ) AS row_no
                          FROM demo_balance_ledger l
                          JOIN demo_user_account a ON a.id = l.user_id
                          JOIN sys_user u ON u.id = a.sys_user_id
                         WHERE a.identity_type IN ('REAL', 'TEST')
                           AND a.player_kind = ?
                           AND u.status <> 'DELETED'
                           AND a.status <> 'DELETED'
                           AND l.created_at >= ? AND l.created_at < ?
                """ + scope.agentIdCondition() + """
                  ) x
                 WHERE x.row_no <= ?
                 ORDER BY x.account_id, x.created_at DESC, x.id DESC
                """, (rs, rowNum) -> new LedgerEvent(
                rs.getLong("id"), rs.getLong("account_id"), rs.getString("operation_type"),
                money(rs.getBigDecimal("amount")), money(rs.getBigDecimal("balance_before")),
                money(rs.getBigDecimal("balance_after")), rs.getObject("operator_user_id", Long.class),
                rs.getString("operator_name"), rs.getString("idempotency_key"),
                rs.getObject("related_bet_id", Long.class), rs.getString("issue_number"),
                rs.getString("reason"), instant(rs.getTimestamp("created_at"))),
                args.toArray());
    }

    public List<LedgerTotals> findLedgerTotals(String playerKind, Instant fromInclusive, Instant toExclusive,
                                                Scope scope) {
        List<Object> args = new ArrayList<>(List.of(playerKind, timestamp(fromInclusive), timestamp(toExclusive)));
        args.addAll(scope.agentIdParameters());
        return jdbc.query("""
                SELECT l.user_id AS account_id,
                       SUM(CASE WHEN l.operation_type IN ('ADMIN_GRANT','ADMIN_ADJUST') AND l.amount > 0
                                THEN l.amount ELSE 0 END) AS top_up,
                       SUM(CASE WHEN l.operation_type IN ('ADMIN_GRANT','ADMIN_ADJUST') AND l.amount < 0
                                THEN -l.amount ELSE 0 END) AS down_amount
                  FROM demo_balance_ledger l
                  JOIN demo_user_account a ON a.id = l.user_id
                  JOIN sys_user u ON u.id = a.sys_user_id
                 WHERE a.identity_type IN ('REAL', 'TEST')
                   AND a.player_kind = ?
                   AND u.status <> 'DELETED'
                   AND a.status <> 'DELETED'
                   AND l.created_at >= ? AND l.created_at < ?
                """ + scope.agentIdCondition() + " GROUP BY l.user_id",
                (rs, rowNum) -> new LedgerTotals(rs.getLong("account_id"),
                        money(rs.getBigDecimal("top_up")), money(rs.getBigDecimal("down_amount"))),
                args.toArray());
    }

    public List<ActionEvent> findActionEvents(String playerKind, Instant fromInclusive, Instant toExclusive,
                                               int perPlayerLimit, Scope scope) {
        // 同上：范围参数必须排在 row_no 之前，否则非 GLOBAL 范围下参数错位。
        List<Object> args = new ArrayList<>(List.of(playerKind, timestamp(fromInclusive), timestamp(toExclusive)));
        args.addAll(scope.agentIdParameters());
        args.add(perPlayerLimit);
        return jdbc.query("""
                SELECT x.id, x.account_id, x.issue_number, x.action_no, x.action_type,
                       x.source_text, x.status, x.attempts, x.error_code, x.error_message,
                       x.message_id, x.bet_id, x.created_at, x.updated_at
                  FROM (
                        SELECT a.id, a.account_id, a.issue_number, a.action_no, a.action_type,
                               a.source_text, a.status, a.attempts, a.error_code, a.error_message,
                               a.message_id, a.bet_id, a.created_at, a.updated_at,
                               ROW_NUMBER() OVER (
                                   PARTITION BY a.account_id ORDER BY a.created_at DESC, a.id DESC
                               ) AS row_no
                          FROM test_player_action a
                          JOIN demo_user_account u ON u.id = a.account_id
                          JOIN sys_user s ON s.id = u.sys_user_id
                         WHERE u.identity_type IN ('REAL', 'TEST')
                           AND u.player_kind = ?
                           AND s.status <> 'DELETED'
                           AND u.status <> 'DELETED'
                           AND a.created_at >= ? AND a.created_at < ?
                """ + scope.agentIdCondition("u") + """
                  ) x
                 WHERE x.row_no <= ?
                 ORDER BY x.account_id, x.created_at DESC, x.id DESC
                """, (rs, rowNum) -> new ActionEvent(
                rs.getLong("id"), rs.getLong("account_id"), rs.getString("issue_number"),
                rs.getInt("action_no"), rs.getString("action_type"), rs.getString("source_text"),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("error_code"),
                rs.getString("error_message"), rs.getObject("message_id", Long.class),
                rs.getObject("bet_id", Long.class), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))),
                args.toArray());
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static BigDecimal nullableMoney(BigDecimal value) {
        return value == null ? null : money(value);
    }

    private static BetEvent mapBetEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BetEvent(rs.getLong("id"), rs.getLong("account_id"), rs.getString("bet_code"),
                rs.getString("issue_number"), rs.getInt("ball_number"), rs.getString("play_type"),
                rs.getString("parameters_text"), money(rs.getBigDecimal("stake")),
                money(rs.getBigDecimal("odds_snapshot")), money(rs.getBigDecimal("rebate_rate_snapshot")),
                money(rs.getBigDecimal("special_rebate_rate_snapshot")),
                nullableMoney(rs.getBigDecimal("selection_unit_stake")), rs.getString("settlement_status"),
                money(rs.getBigDecimal("net_profit")), rs.getString("explanation"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("settled_at")));
    }

    public record PlayerAccount(long userId, long accountId, long agentId, String rebateMode,
                                String memberCode, String userCode,
                                String displayName, String playerKind, BigDecimal openingBalance,
                                BigDecimal closingBalance) {}

    public record BetEvent(long id, long accountId, String betCode, String issueNumber, int ballNumber,
                           String playType, String parametersText, BigDecimal stake, BigDecimal odds,
                           BigDecimal rebateRate, BigDecimal specialRebateRate,
                           BigDecimal selectionUnitStake, String settlementStatus,
                           BigDecimal netProfit, String explanation, Instant createdAt, Instant settledAt) {
        public BetEvent(long id, long accountId, String betCode, String issueNumber, int ballNumber,
                        String playType, String parametersText, BigDecimal stake, BigDecimal odds,
                        BigDecimal rebateRate, BigDecimal specialRebateRate, String settlementStatus,
                        BigDecimal netProfit, String explanation, Instant createdAt, Instant settledAt) {
            this(id, accountId, betCode, issueNumber, ballNumber, playType, parametersText, stake, odds,
                    rebateRate, specialRebateRate, null, settlementStatus, netProfit, explanation,
                    createdAt, settledAt);
        }
    }

    public record LedgerEvent(long id, long accountId, String operationType, BigDecimal amount,
                              BigDecimal balanceBefore, BigDecimal balanceAfter, Long operatorUserId,
                              String operatorName, String idempotencyKey, Long relatedBetId,
                              String issueNumber, String reason, Instant createdAt) {}

    public record LedgerTotals(long accountId, BigDecimal topUp, BigDecimal down) {}

    public record ActionEvent(long id, long accountId, String issueNumber, int actionNo,
                              String actionType, String sourceText, String status, int attempts,
                              String errorCode, String errorMessage, Long messageId, Long betId,
                              Instant createdAt, Instant updatedAt) {}
}
