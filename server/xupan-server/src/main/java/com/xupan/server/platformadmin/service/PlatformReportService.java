package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlatformReportService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final PlatformAdminAccessService accessService;

    public PlatformReportService(JdbcTemplate jdbc, PermissionService permissionService,
                                  PlatformAdminAccessService accessService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.accessService = accessService;
    }

    @Transactional(readOnly = true)
    public List<ScoreFlowRow> scoreFlow(String day, String day3, Long subAccountId, long operator) {
        require(operator);
        BusinessRange range = resolveRange(day, day3);
        Long scopedSubAccountId = accessService.scopedGroupId(operator, subAccountId);
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.created_at, l.operation_type, l.amount, l.balance_after, l.reason,
                       a.member_code, a.display_name player_name, ag.id machine_id,
                       ag.display_name machine_name, g.username sub_account
                  FROM demo_balance_ledger l
                  JOIN demo_user_account a ON a.id = l.user_id
                  JOIN agent ag ON ag.id = a.agent_id
                  LEFT JOIN agent_group g ON g.id = ag.group_id
                 WHERE l.created_at >= ? AND l.created_at < ?
                """);
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        if (scopedSubAccountId != null) { sql.append(" AND g.id = ?"); args.add(scopedSubAccountId); }
        sql.append(" ORDER BY l.id DESC LIMIT 2000");
        return jdbc.query(sql.toString(), (rs, n) -> new ScoreFlowRow(rs.getLong("id"),
                instant(rs.getTimestamp("created_at")), rs.getString("operation_type"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("balance_after"), rs.getString("reason"),
                rs.getString("member_code"), rs.getString("player_name"), rs.getLong("machine_id"),
                rs.getString("machine_name"), rs.getString("sub_account")), args.toArray());
    }

    @Transactional(readOnly = true)
    public ProfitReport profitReport(String day, String day3, Long subAccountId, long operator) {
        require(operator);
        BusinessRange range = resolveRange(day, day3);
        Long scopedSubAccountId = accessService.scopedGroupId(operator, subAccountId);
        StringBuilder machineSql = new StringBuilder("""
                SELECT ag.id, ag.agent_code, ag.display_name, g.id group_id, g.username group_username,
                       ag.score,
                       COALESCE((SELECT SUM(a.balance) FROM demo_user_account a
                                  WHERE a.agent_id=ag.id AND a.status <> 'DELETED'), 0) player_balance,
                       COALESCE((SELECT SUM(b.stake) FROM game_bet b
                                  JOIN demo_user_account a ON a.id=b.user_id
                                 WHERE a.agent_id=ag.id AND b.created_at >= ? AND b.created_at < ?
                                   AND b.settlement_status IN ('WIN','DRAW','LOSE')), 0) total_flow
                  FROM agent ag
                  LEFT JOIN agent_group g ON g.id=ag.group_id
                 WHERE ag.system_owned=FALSE
                """);
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(range.from()));
        args.add(Timestamp.from(range.to()));
        if (scopedSubAccountId != null) { machineSql.append(" AND g.id=?"); args.add(scopedSubAccountId); }
        machineSql.append(" ORDER BY ag.id");
        List<MachineProfitRow> base = jdbc.query(machineSql.toString(), (rs, n) -> new MachineProfitRow(
                rs.getLong("id"), rs.getString("agent_code"), rs.getString("display_name"),
                nullableLong(rs, "group_id"), rs.getString("group_username"), rs.getBigDecimal("score"),
                rs.getBigDecimal("player_balance"), rs.getBigDecimal("total_flow"),
                ZERO, ZERO, ZERO, ZERO, ZERO, ZERO), args.toArray());

        Map<Long, BalanceMovement> movements = balanceMovements(range, scopedSubAccountId);
        Map<Long, FlowMetrics> flowMetrics = flowMetrics(range, scopedSubAccountId);
        List<MachineProfitRow> rows = base.stream().map(row -> {
            BalanceMovement movement = movements.getOrDefault(row.machineId(), BalanceMovement.ZERO);
            FlowMetrics flow = flowMetrics.getOrDefault(row.machineId(), FlowMetrics.ZERO);
            return new MachineProfitRow(row.machineId(), row.machineCode(), row.machineName(), row.groupId(),
                    row.groupUsername(), row.score(), row.playerBalance(), row.totalFlow(),
                    flow.singleFlow(), flow.doubleFlow(), flow.profit(), flow.fanShui(),
                    movement.up(), movement.down());
        }).toList();
        BigDecimal totalRemaining = rows.stream().map(row -> row.score().add(row.playerBalance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFlow = rows.stream().map(MachineProfitRow::totalFlow)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSingle = rows.stream().map(MachineProfitRow::totalSingleFlow)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDouble = rows.stream().map(MachineProfitRow::totalDoubleFlow)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalProfit = rows.stream().map(MachineProfitRow::totalProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFanShui = rows.stream().map(MachineProfitRow::totalFanShui)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalUp = rows.stream().map(MachineProfitRow::totalUp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDown = rows.stream().map(MachineProfitRow::totalDown)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ProfitReport(range.day(), range.day3(), range.from(), range.to(), rows,
                totalRemaining, totalFlow, totalSingle, totalDouble, totalProfit, totalFanShui,
                totalUp, totalDown, totalUp.subtract(totalDown));
    }

    private Map<Long, FlowMetrics> flowMetrics(BusinessRange range, Long subAccountId) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.agent_id, b.play_type, b.parameters_text, b.stake, b.odds_snapshot,
                       b.net_profit, b.settlement_status, b.rebate_rate_snapshot,
                       b.special_rebate_rate_snapshot
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id=b.user_id
                  JOIN agent ag ON ag.id=a.agent_id
                 WHERE ag.system_owned=FALSE
                   AND b.created_at >= ? AND b.created_at < ?
                   AND b.settlement_status IN ('WIN','DRAW','LOSE')
                """);
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        if (subAccountId != null) { sql.append(" AND ag.group_id=?"); args.add(subAccountId); }
        Map<Long, FlowMetrics> result = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            long machineId = rs.getLong("agent_id");
            FlowMetrics current = result.getOrDefault(machineId, FlowMetrics.ZERO);
            result.put(machineId, current.plus(metricsFor(
                    PlayType.valueOf(rs.getString("play_type")), rs.getString("parameters_text"),
                    rs.getBigDecimal("stake"), rs.getBigDecimal("odds_snapshot"),
                    rs.getBigDecimal("net_profit"), rs.getString("settlement_status"),
                    rs.getBigDecimal("rebate_rate_snapshot"),
                    rs.getBigDecimal("special_rebate_rate_snapshot"))));
        }, args.toArray());
        return result;
    }

    private static FlowMetrics metricsFor(PlayType playType, String parametersText, BigDecimal stake,
                                          BigDecimal odds, BigDecimal netProfit, String settlementStatus,
                                          BigDecimal rebateRate, BigDecimal specialRebateRate) {
        BigDecimal normalizedStake = money(stake);
        BigDecimal normalizedProfit = money(netProfit);
        BigDecimal singleFlow = ZERO;
        BigDecimal doubleFlow = ZERO;
        BigDecimal fanShui = ZERO;
        if ("WIN".equals(settlementStatus)) {
            boolean special = playType == PlayType.SPECIAL;
            BigDecimal payout = normalizedStake.multiply(odds == null ? BigDecimal.ZERO : odds).setScale(2, RoundingMode.HALF_UP);
            singleFlow = special ? payout : normalizedProfit;
            doubleFlow = special ? payout : switch (playType) {
                case FAN -> money(normalizedStake.multiply(new BigDecimal("3")));
                case CAR -> money(normalizedStake.divide(new BigDecimal("3"), 6, RoundingMode.HALF_UP));
                case TONG -> money(normalizedStake.divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP));
                case NONE -> isTong(playType, parametersText)
                        ? money(normalizedStake.divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP))
                        : normalizedProfit;
                default -> normalizedProfit;
            };
            BigDecimal rate = special ? rate(specialRebateRate) : rate(rebateRate);
            BigDecimal rebateBase = special ? normalizedStake : normalizedProfit;
            fanShui = money(rebateBase.multiply(rate).divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP));
        } else if ("LOSE".equals(settlementStatus)) {
            doubleFlow = isTong(playType, parametersText)
                    ? money(normalizedStake.divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP))
                    : normalizedStake;
        }
        return new FlowMetrics(singleFlow, doubleFlow, normalizedProfit, fanShui);
    }

    private static boolean isTong(PlayType playType, String parametersText) {
        return playType == PlayType.TONG
                || (playType == PlayType.NONE && parseParameters(parametersText).size() == 3);
    }

    private static List<Integer> parseParameters(String parametersText) {
        return java.util.Arrays.stream(parametersText == null ? new String[0] : parametersText.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::valueOf)
                .toList();
    }

    private Map<Long, BalanceMovement> balanceMovements(BusinessRange range, Long subAccountId) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.agent_id,
                       COALESCE(SUM(CASE WHEN l.amount > 0 THEN l.amount ELSE 0 END), 0) total_up,
                       COALESCE(SUM(CASE WHEN l.amount < 0 THEN -l.amount ELSE 0 END), 0) total_down
                  FROM demo_balance_ledger l
                  JOIN demo_user_account a ON a.id=l.user_id
                  LEFT JOIN agent g ON g.id=a.agent_id
                 WHERE l.created_at >= ? AND l.created_at < ?
                   AND l.operation_type IN ('ADMIN_GRANT','ADMIN_ADJUST')
                """);
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        if (subAccountId != null) { sql.append(" AND g.group_id=?"); args.add(subAccountId); }
        sql.append(" GROUP BY a.agent_id");
        Map<Long, BalanceMovement> result = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            result.put(rs.getLong("agent_id"),
                    new BalanceMovement(rs.getBigDecimal("total_up"), rs.getBigDecimal("total_down")));
        }, args.toArray());
        return result;
    }

    private void require(long operator) {
        if (operator <= 0 || !permissionService.hasPermission(operator, "REPORT_READ")) {
            throw BusinessException.forbidden("REPORT_FORBIDDEN", "没有报表统计权限");
        }
    }

    private static BusinessRange resolveRange(String day, String day3) {
        LocalDate start = parseDate(day, LocalDate.now(BUSINESS_ZONE));
        LocalDate end = parseDate(day3, start);
        if (end.isBefore(start)) throw BusinessException.badRequest("REPORT_DATE_INVALID", "结束日期不能早于开始日期");
        if (start.plusDays(366).isBefore(end)) throw BusinessException.badRequest("REPORT_DATE_INVALID", "查询范围不能超过一年");
        Instant from = start.atTime(6, 0).atZone(BUSINESS_ZONE).toInstant();
        Instant to = end.plusDays(1).atTime(6, 0).atZone(BUSINESS_ZONE).toInstant();
        return new BusinessRange(start, end, from, to);
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException exception) { throw BusinessException.badRequest("REPORT_DATE_INVALID", "日期格式必须为 yyyy-MM-dd"); }
    }

    private static BigDecimal rate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private record BusinessRange(LocalDate day, LocalDate day3, Instant from, Instant to) {}
    private record FlowMetrics(BigDecimal singleFlow, BigDecimal doubleFlow, BigDecimal profit,
                               BigDecimal fanShui) {
        static final FlowMetrics ZERO = new FlowMetrics(PlatformReportService.ZERO, PlatformReportService.ZERO, PlatformReportService.ZERO, PlatformReportService.ZERO);
        FlowMetrics plus(FlowMetrics other) {
            return new FlowMetrics(money(singleFlow.add(other.singleFlow)),
                    money(doubleFlow.add(other.doubleFlow)), money(profit.add(other.profit)),
                    money(fanShui.add(other.fanShui)));
        }
    }
    private record BalanceMovement(BigDecimal up, BigDecimal down) {
        static final BalanceMovement ZERO = new BalanceMovement(BigDecimal.ZERO, BigDecimal.ZERO);
    }
    public record ScoreFlowRow(long id, Instant createdAt, String operationType, BigDecimal amount,
                               BigDecimal balanceAfter, String reason, String memberCode, String playerName,
                               long machineId, String machineName, String subAccount) {}
    public record MachineProfitRow(long machineId, String machineCode, String machineName, Long groupId,
                                   String groupUsername, BigDecimal score, BigDecimal playerBalance,
                                   BigDecimal totalFlow, BigDecimal totalSingleFlow, BigDecimal totalDoubleFlow,
                                   BigDecimal totalProfit, BigDecimal totalFanShui, BigDecimal totalUp,
                                   BigDecimal totalDown) {}
    public record ProfitReport(LocalDate day, LocalDate day3, Instant fromInclusive, Instant toExclusive,
                               List<MachineProfitRow> machines, BigDecimal totalRemaining, BigDecimal totalFlow,
                               BigDecimal totalSingleFlow, BigDecimal totalDoubleFlow, BigDecimal totalProfit,
                               BigDecimal totalFanShui, BigDecimal totalUp, BigDecimal totalDown,
                               BigDecimal totalUpDown) {}
}




