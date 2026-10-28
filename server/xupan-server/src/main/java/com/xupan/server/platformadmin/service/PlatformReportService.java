package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.MachineBettingConfig;
import com.xupan.server.game.service.BetFlowMetricsCalculator;
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
                       COALESCE(a.member_code, l.player_code_snapshot) member_code,
                       COALESCE(a.display_name, l.player_name_snapshot) player_name,
                       ag.id machine_id,
                       ag.display_name machine_name, g.username sub_account
                  FROM demo_balance_ledger l
                  LEFT JOIN demo_user_account a ON a.id = l.user_id
                  JOIN agent ag ON ag.id = COALESCE(a.agent_id, l.agent_id_snapshot)
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
                       ag.score, COALESCE(ag.rebate_mode, 'SINGLE') rebate_mode,
                       COALESCE((SELECT SUM(a.balance) FROM demo_user_account a
                                  WHERE a.agent_id=ag.id AND a.status <> 'DELETED'), 0) player_balance,
                       COALESCE((SELECT SUM(b.stake) FROM game_bet b
                                  LEFT JOIN demo_user_account a ON a.id=b.user_id
                                 WHERE COALESCE(a.agent_id, b.agent_id_snapshot)=ag.id
                                   AND b.created_at >= ? AND b.created_at < ?
                                   AND b.settlement_status IN ('WIN','DRAW','LOSE')), 0) total_flow
                  FROM agent ag
                  LEFT JOIN agent_group g ON g.id=ag.group_id
                 WHERE ag.system_owned=FALSE AND ag.deleted_at IS NULL
                """);
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(range.from()));
        args.add(Timestamp.from(range.to()));
        if (scopedSubAccountId != null) { machineSql.append(" AND g.id=?"); args.add(scopedSubAccountId); }
        machineSql.append(" ORDER BY ag.id");
        List<MachineBase> base = jdbc.query(machineSql.toString(), (rs, n) -> new MachineBase(
                rs.getLong("id"), rs.getString("agent_code"), rs.getString("display_name"),
                nullableLong(rs, "group_id"), rs.getString("group_username"), rs.getBigDecimal("score"),
                rs.getBigDecimal("player_balance"), rs.getBigDecimal("total_flow"),
                rs.getString("rebate_mode")), args.toArray());

        Map<Long, BalanceMovement> movements = balanceMovements(range, scopedSubAccountId);
        Map<Long, BetFlowMetricsCalculator.Metrics> flowMetrics = flowMetrics(range, scopedSubAccountId);
        List<MachineProfitRow> rows = base.stream().map(row -> {
            BalanceMovement movement = movements.getOrDefault(row.machineId(), BalanceMovement.ZERO);
            BetFlowMetricsCalculator.Metrics flow = flowMetrics.getOrDefault(
                    row.machineId(), BetFlowMetricsCalculator.Metrics.ZERO);
            // BY220 x_robot.ls：单边显示单边返水，双边显示双边返水，生效口径随机器配置切换。
            String rebateMode = normalizeRebateMode(row.rebateMode());
            BigDecimal effectiveRebate = MachineBettingConfig.DOUBLE.equals(rebateMode)
                    ? flow.doubleFanShui()
                    : flow.fanShui();
            return new MachineProfitRow(row.machineId(), row.machineCode(), row.machineName(), row.groupId(),
                    row.groupUsername(), row.score(), row.playerBalance(), row.totalFlow(),
                    flow.singleFlow(), flow.doubleFlow(), flow.profit(), flow.fanShui(), flow.doubleFanShui(),
                    movement.up(), movement.down(), rebateMode, effectiveRebate);
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
        BigDecimal totalRebateDouble = rows.stream().map(MachineProfitRow::rebateDouble)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEffectiveRebate = rows.stream().map(MachineProfitRow::effectiveRebate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalUp = rows.stream().map(MachineProfitRow::totalUp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDown = rows.stream().map(MachineProfitRow::totalDown)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ProfitReport(range.day(), range.day3(), range.from(), range.to(), rows,
                totalRemaining, totalFlow, totalSingle, totalDouble, totalProfit, totalFanShui,
                totalRebateDouble, totalEffectiveRebate, totalUp, totalDown, totalUp.subtract(totalDown));
    }

    private Map<Long, BetFlowMetricsCalculator.Metrics> flowMetrics(BusinessRange range, Long subAccountId) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(a.agent_id, b.agent_id_snapshot) AS agent_id, b.play_type, b.parameters_text,
                       b.stake, b.odds_snapshot,
                       b.net_profit, b.settlement_status, b.rebate_rate_snapshot,
                       b.special_rebate_rate_snapshot,
                       (SELECT MIN(s.unit_stake) FROM game_bet_selection s WHERE s.bet_id = b.id)
                           AS selection_unit_stake
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id=b.user_id
                  JOIN agent ag ON ag.id=COALESCE(a.agent_id, b.agent_id_snapshot)
                 WHERE ag.system_owned=FALSE
                   AND b.created_at >= ? AND b.created_at < ?
                   AND b.settlement_status IN ('WIN','DRAW','LOSE')
                """);
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        if (subAccountId != null) { sql.append(" AND ag.group_id=?"); args.add(subAccountId); }
        Map<Long, BetFlowMetricsCalculator.Metrics> result = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            long machineId = rs.getLong("agent_id");
            com.xupan.server.game.domain.PlayType playType =
                    com.xupan.server.game.domain.PlayType.valueOf(rs.getString("play_type"));
            BigDecimal stake = rs.getBigDecimal("stake");
            BigDecimal unitStake = playType == com.xupan.server.game.domain.PlayType.SPECIAL
                    ? rs.getBigDecimal("selection_unit_stake") : stake;
            BetFlowMetricsCalculator.Metrics current = result.getOrDefault(
                    machineId, BetFlowMetricsCalculator.Metrics.ZERO);
            result.put(machineId, current.plus(BetFlowMetricsCalculator.calculate(
                    playType,
                    rs.getString("parameters_text"),
                    stake, rs.getBigDecimal("odds_snapshot"),
                    rs.getBigDecimal("net_profit"), rs.getString("settlement_status"),
                    rs.getBigDecimal("rebate_rate_snapshot"),
                    rs.getBigDecimal("special_rebate_rate_snapshot"), unitStake)));
        }, args.toArray());
        return result;
    }

    private Map<Long, BalanceMovement> balanceMovements(BusinessRange range, Long subAccountId) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(a.agent_id, l.agent_id_snapshot) AS agent_id,
                       COALESCE(SUM(CASE WHEN l.amount > 0 THEN l.amount ELSE 0 END), 0) total_up,
                       COALESCE(SUM(CASE WHEN l.amount < 0 THEN -l.amount ELSE 0 END), 0) total_down
                  FROM demo_balance_ledger l
                  LEFT JOIN demo_user_account a ON a.id=l.user_id
                  LEFT JOIN agent g ON g.id=COALESCE(a.agent_id, l.agent_id_snapshot)
                 WHERE l.created_at >= ? AND l.created_at < ?
                   AND l.operation_type IN ('ADMIN_GRANT','ADMIN_ADJUST')
                """);
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        if (subAccountId != null) { sql.append(" AND g.group_id=?"); args.add(subAccountId); }
        sql.append(" GROUP BY COALESCE(a.agent_id, l.agent_id_snapshot)");
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

    /** BY220 x_robot.ls：单边/双边返水口径，未知值回退单边。 */
    private static String normalizeRebateMode(String value) {
        return MachineBettingConfig.DOUBLE.equalsIgnoreCase(value == null ? null : value.trim())
                ? MachineBettingConfig.DOUBLE
                : MachineBettingConfig.SINGLE;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private record BusinessRange(LocalDate day, LocalDate day3, Instant from, Instant to) {}
    private record BalanceMovement(BigDecimal up, BigDecimal down) {
        static final BalanceMovement ZERO = new BalanceMovement(BigDecimal.ZERO, BigDecimal.ZERO);
    }
    public record ScoreFlowRow(long id, Instant createdAt, String operationType, BigDecimal amount,
                               BigDecimal balanceAfter, String reason, String memberCode, String playerName,
                               long machineId, String machineName, String subAccount) {}
    public record MachineProfitRow(long machineId, String machineCode, String machineName, Long groupId,
                                   String groupUsername, BigDecimal score, BigDecimal playerBalance,
                                   BigDecimal totalFlow, BigDecimal totalSingleFlow, BigDecimal totalDoubleFlow,
                                   BigDecimal totalProfit, BigDecimal totalFanShui, BigDecimal rebateDouble,
                                   BigDecimal totalUp, BigDecimal totalDown, String rebateMode,
                                   BigDecimal effectiveRebate) {}
    public record ProfitReport(LocalDate day, LocalDate day3, Instant fromInclusive, Instant toExclusive,
                               List<MachineProfitRow> machines, BigDecimal totalRemaining, BigDecimal totalFlow,
                               BigDecimal totalSingleFlow, BigDecimal totalDoubleFlow, BigDecimal totalProfit,
                               BigDecimal totalFanShui, BigDecimal totalRebateDouble,
                               BigDecimal totalEffectiveRebate, BigDecimal totalUp,
                               BigDecimal totalDown, BigDecimal totalUpDown) {}

    private record MachineBase(long machineId, String machineCode, String machineName, Long groupId,
                               String groupUsername, BigDecimal score, BigDecimal playerBalance,
                               BigDecimal totalFlow, String rebateMode) {}
}




