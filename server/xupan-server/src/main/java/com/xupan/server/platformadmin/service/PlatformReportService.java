package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.MachineBettingConfig;
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
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal THREE = new BigDecimal("3");
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
                       ag.score, COALESCE(ag.rebate_mode, 'SINGLE') rebate_mode,
                       COALESCE((SELECT SUM(a.balance) FROM demo_user_account a
                                  WHERE a.agent_id=ag.id AND a.status <> 'DELETED'), 0) player_balance,
                       COALESCE((SELECT SUM(b.stake) FROM game_bet b
                                  JOIN demo_user_account a ON a.id=b.user_id
                                 WHERE a.agent_id=ag.id AND b.created_at >= ? AND b.created_at < ?
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
        Map<Long, FlowMetrics> flowMetrics = flowMetrics(range, scopedSubAccountId);
        List<MachineProfitRow> rows = base.stream().map(row -> {
            BalanceMovement movement = movements.getOrDefault(row.machineId(), BalanceMovement.ZERO);
            FlowMetrics flow = flowMetrics.getOrDefault(row.machineId(), FlowMetrics.ZERO);
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

    /**
     * 计算单笔已结算注单的报表指标，口径逐行对应 BY220 application/common.php 的
     * getRecordData（4397-4449）、getShuang（3906-3922）、getShuangs（3924-3944）。
     *
     * <p>status=1 表示已结算：WIN=赢单（ying=1）、LOSE=输单（ying=0）、DRAW=和单（ying=3）。
     * 和单既不计双边流水（common.php:4444-4447 只处理 ying==0），也不计双边返水
     * （common.php:4424 条件为 ying != 3）。</p>
     */
    private static FlowMetrics metricsFor(PlayType playType, String parametersText, BigDecimal stake,
                                          BigDecimal odds, BigDecimal netProfit, String settlementStatus,
                                          BigDecimal rebateRate, BigDecimal specialRebateRate) {
        BigDecimal normalizedStake = money(stake);
        BigDecimal normalizedProfit = money(netProfit);
        BigDecimal normalizedOdds = odds == null ? BigDecimal.ZERO : odds;
        boolean special = playType == PlayType.SPECIAL;
        boolean won = "WIN".equals(settlementStatus);
        boolean lost = "LOSE".equals(settlementStatus);
        BigDecimal singleFlow = ZERO;
        BigDecimal doubleFlow = ZERO;
        BigDecimal fanShui = ZERO;
        BigDecimal doubleFanShui = ZERO;
        if (won) {
            // 单边流水（common.php:4403-4407 累加 yingScore）：
            // 特码取 本金×赔率（BY220 特码 yingScore = 特投注额 × tePeilv），其余取净收益
            BigDecimal payout = money(normalizedStake.multiply(normalizedOdds));
            singleFlow = special ? payout : normalizedProfit;
            // 双边流水（common.php:4422 累加 getShuang）：见 wonDoubleFlow
            doubleFlow = wonDoubleFlow(playType, parametersText, normalizedStake);
            // 单边返水（common.php:4409-4421 累加 recordFan，只在 ying==1 时计算）：
            // 特码 = 本金×特码返水（4410 行对 teFanshui 不做除 100）；其余 = 净收益×普通返水/100（4416-4419）
            fanShui = special
                    ? money(normalizedStake.multiply(rate(specialRebateRate)))
                    : rebate(normalizedProfit, rebateRate);
        } else if (lost) {
            // 双边流水（common.php:4444-4447）：status=1 且 ying==0 的败单直接累加 score（全额本金），
            // 与 getShuang/getShuangs 无关，所以通类和三位无都不减半
            doubleFlow = normalizedStake;
        }
        if (won || lost) {
            // 双边返水（common.php:4424-4434 累加 recordFans，条件为 status==1 且 ying != 3，赢单输单都算）
            BigDecimal base;
            if (special) {
                // common.php:4426 特码取 totalYing；totalYing 只在特码赢单时赋值（4189/4201/4211），
                // 初始化在 4126 为 0，因此特码输单的双边返水基数为 0
                base = won ? normalizedStake.multiply(normalizedOdds) : ZERO;
            } else {
                base = doubleRebateBase(playType, parametersText, normalizedStake, won);
            }
            doubleFanShui = rebate(base, rebateRate);
        }
        return new FlowMetrics(singleFlow, doubleFlow, normalizedProfit, fanShui, doubleFanShui);
    }

    /**
     * BY220 getShuang（common.php:3906-3922）：赢单的双边流水基数。
     *
     * <ul>
     *   <li>车/推：本金/3（3908 的 $chetui 与 3913-3914 返回 totalYing = score/3）</li>
     *   <li>通类：本金/2（3909 的 $tong 与 3915-3916）</li>
     *   <li>其余（$wanfaArr 的三位无与加、番、念/严、角、正、单双、大小、特码）：本金（3917-3920）</li>
     * </ul>
     *
     * <p>注意 3909 的 $tong 数组同时包含「1通23」和「12无3」两种写法：xupan 中前者是 TONG 玩法，
     * 后者是 3 参数的 NONE（BetTextParser.java:120 解析 "12无3"，BetCommandFormatter.java:47 还原），
     * 所以通类判定必须同时覆盖 TONG 与 3 参数 NONE。</p>
     */
    private static BigDecimal wonDoubleFlow(PlayType playType, String parametersText, BigDecimal stake) {
        if (playType == PlayType.CAR) {
            return divide(stake, THREE);
        }
        if (playType == PlayType.TONG || isThreeParameterNone(playType, parametersText)) {
            return divide(stake, TWO);
        }
        // 番（4256-4261 的 totalYing = 本金×3）不在这里取 3 倍；特码也落到这里取本金
        return stake;
    }

    /**
     * BY220 getShuangs（common.php:3924-3944）：双边返水基数，赢单和输单共用。
     *
     * <ul>
     *   <li>车/推：赢单本金/3（3932-3933），输单本金（3934-3935）</li>
     *   <li>通类：赢单和输单都取 本金/2（3937-3938）</li>
     *   <li>其余（3939-3942，含 $wanfaArr 的三位无与加）：本金</li>
     * </ul>
     */
    private static BigDecimal doubleRebateBase(PlayType playType, String parametersText, BigDecimal stake,
                                               boolean won) {
        if (playType == PlayType.CAR) {
            return won ? divide(stake, THREE) : stake;
        }
        if (playType == PlayType.TONG || isThreeParameterNone(playType, parametersText)) {
            return divide(stake, TWO);
        }
        return stake;
    }

    /** 是否为 BY220 $tong（common.php:3909）里的「二无」写法，即 xupan 中参数为 3 个的 NONE（如 12无3）。 */
    private static boolean isThreeParameterNone(PlayType playType, String parametersText) {
        if (playType != PlayType.NONE || parametersText == null) {
            return false;
        }
        int count = 0;
        for (String value : parametersText.split(",")) {
            if (!value.isBlank()) {
                count++;
            }
        }
        return count == 3;
    }

    /** 返水 = 基数 × 返水百分数 / 100，保留 2 位小数。 */
    private static BigDecimal rebate(BigDecimal base, BigDecimal rateValue) {
        return money(base.multiply(rate(rateValue)).divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP));
    }

    /** 按小数精确相除后再保留 2 位小数（本金/3、本金/2）。 */
    private static BigDecimal divide(BigDecimal value, BigDecimal divisor) {
        return money(value.divide(divisor, 6, RoundingMode.HALF_UP));
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
    private record FlowMetrics(BigDecimal singleFlow, BigDecimal doubleFlow, BigDecimal profit,
                               BigDecimal fanShui, BigDecimal doubleFanShui) {
        static final FlowMetrics ZERO = new FlowMetrics(PlatformReportService.ZERO, PlatformReportService.ZERO,
                PlatformReportService.ZERO, PlatformReportService.ZERO, PlatformReportService.ZERO);
        FlowMetrics plus(FlowMetrics other) {
            return new FlowMetrics(money(singleFlow.add(other.singleFlow)),
                    money(doubleFlow.add(other.doubleFlow)), money(profit.add(other.profit)),
                    money(fanShui.add(other.fanShui)), money(doubleFanShui.add(other.doubleFanShui)));
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




