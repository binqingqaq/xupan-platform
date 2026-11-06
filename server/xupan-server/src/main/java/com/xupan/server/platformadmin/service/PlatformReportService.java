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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlatformReportService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final int DETAIL_LIMIT = 200;

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
    public ScoreFlowPage scoreFlow(String day, String day3, Long subAccountId,
                                   int page, int pageSize, long operator) {
        require(operator);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw BusinessException.badRequest("REPORT_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        BusinessRange range = resolveRange(day, day3);
        Long scopedSubAccountId = accessService.scopedGroupId(operator, subAccountId);
        String betKey = playerKey("a", "b");
        String ledgerKey = playerKey("a", "l");
        StringBuilder peopleSql = new StringBuilder("""
                SELECT player_key, MAX(user_id) user_id, MAX(account_id) account_id,
                       MAX(member_code) member_code, MAX(player_name) player_name,
                       machine_id, MAX(machine_name) machine_name, MAX(sub_account) sub_account,
                       MAX(rebate_mode) rebate_mode
                  FROM (
                        SELECT %s player_key, a.sys_user_id user_id, a.id account_id,
                               COALESCE(a.member_code, b.player_code_snapshot) member_code,
                               COALESCE(a.display_name, b.player_name_snapshot) player_name,
                               ag.id machine_id, ag.display_name machine_name, g.username sub_account,
                               COALESCE(ag.rebate_mode,'SINGLE') rebate_mode
                          FROM game_bet b
                          LEFT JOIN demo_user_account a ON a.id=b.user_id
                          JOIN agent ag ON ag.id=COALESCE(a.agent_id, b.agent_id_snapshot)
                          LEFT JOIN agent_group g ON g.id=ag.group_id
                         WHERE COALESCE(b.player_kind_snapshot, a.player_kind)='NORMAL'
                           AND b.settlement_status <> 'CANCELED'
                           AND b.created_at >= ? AND b.created_at < ?
                           AND ag.system_owned=FALSE
                """.formatted(betKey));
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        appendGroupFilter(peopleSql, args, scopedSubAccountId, "g.id");
        peopleSql.append("""
                        UNION ALL
                        SELECT %s player_key, a.sys_user_id user_id, a.id account_id,
                               COALESCE(a.member_code, l.player_code_snapshot) member_code,
                               COALESCE(a.display_name, l.player_name_snapshot) player_name,
                               ag.id machine_id, ag.display_name machine_name, g.username sub_account,
                               COALESCE(ag.rebate_mode,'SINGLE') rebate_mode
                          FROM demo_balance_ledger l
                          LEFT JOIN demo_user_account a ON a.id=l.user_id
                          JOIN agent ag ON ag.id=COALESCE(a.agent_id, l.agent_id_snapshot)
                          LEFT JOIN agent_group g ON g.id=ag.group_id
                         WHERE COALESCE(l.player_kind_snapshot, a.player_kind)='NORMAL'
                           AND l.operation_type IN ('ADMIN_GRANT','ADMIN_ADJUST')
                           AND l.created_at >= ? AND l.created_at < ?
                           AND ag.system_owned=FALSE
                """.formatted(ledgerKey));
        args.add(Timestamp.from(range.from()));
        args.add(Timestamp.from(range.to()));
        appendGroupFilter(peopleSql, args, scopedSubAccountId, "g.id");
        peopleSql.append("""
                  ) events
                 GROUP BY player_key, machine_id
                 ORDER BY player_name, member_code, player_key
                 LIMIT ? OFFSET ?
                """);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (page - 1) * pageSize);
        List<ScoreFlowPlayerAccumulator> pagePeople = jdbc.query(peopleSql.toString(), (rs, n) ->
                new ScoreFlowPlayerAccumulator(rs.getString("player_key"),
                        nullableLong(rs, "user_id"), nullableLong(rs, "account_id"),
                        rs.getString("member_code"), rs.getString("player_name"), rs.getLong("machine_id"),
                        rs.getString("machine_name"), rs.getString("sub_account"), rs.getString("rebate_mode")),
                pageArgs.toArray());

        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM (SELECT player_key, machine_id FROM (")
                .append(peopleSql.substring(peopleSql.indexOf("SELECT "), peopleSql.indexOf("ORDER BY player_name")))
                .append(") count_events GROUP BY player_key, machine_id) people_count");
        List<Object> countArgs = new ArrayList<>(args);
        Long total = jdbc.queryForObject(countSql.toString(), Long.class, countArgs.toArray());
        String participantSql = peopleSql.substring(peopleSql.indexOf("SELECT "),
                peopleSql.indexOf("ORDER BY player_name"));
        List<ScoreFlowPlayerAccumulator> participants = jdbc.query(participantSql, (rs, n) ->
                new ScoreFlowPlayerAccumulator(rs.getString("player_key"), nullableLong(rs, "user_id"),
                        nullableLong(rs, "account_id"), rs.getString("member_code"), rs.getString("player_name"),
                        rs.getLong("machine_id"), rs.getString("machine_name"), rs.getString("sub_account"),
                        rs.getString("rebate_mode")), args.toArray());

        Map<String, ScoreFlowPlayerAccumulator> pageByKey = new LinkedHashMap<>();
        pagePeople.forEach(person -> pageByKey.put(person.playerKey(), person));
        Map<Long, ScoreFlowMetricAccumulator> allMetrics = new HashMap<>();
        // The metrics scan covers the full filter scope; the page only limits player rows and details.
        scanScoreFlowBets(range, scopedSubAccountId, allMetrics, pageByKey);
        scanScoreFlowPointOperations(range, scopedSubAccountId, allMetrics, pageByKey);

        for (ScoreFlowPlayerAccumulator person : pagePeople) {
            person.setBalances(balanceAtRange(person, range));
        }
        loadScoreFlowDetails(pagePeople, range);

        List<ScoreFlowPlayerRow> items = pagePeople.stream().map(ScoreFlowPlayerAccumulator::toRow).toList();
        ScoreFlowSummary summary = scoreFlowSummary(range, scopedSubAccountId, allMetrics, participants);
        return new ScoreFlowPage(items, page, pageSize, total == null ? 0 : total, summary);
    }

    @Transactional(readOnly = true)
    public ProfitReport profitReport(String day, String day3, Long subAccountId, long operator) {
        require(operator);
        BusinessRange range = resolveRange(day, day3);
        Long scopedSubAccountId = accessService.scopedGroupId(operator, subAccountId);
        StringBuilder machineSql = new StringBuilder("""
                SELECT ag.id, ag.agent_code, ag.display_name, g.id group_id, g.username group_username,
                       ag.score, COALESCE(ag.rebate_mode, 'SINGLE') rebate_mode,
                       0.00 player_balance,
                       COALESCE((SELECT SUM(b.stake) FROM game_bet b
                                  LEFT JOIN demo_user_account a ON a.id=b.user_id
                                 WHERE COALESCE(a.agent_id, b.agent_id_snapshot)=ag.id
                                   AND b.created_at >= ? AND b.created_at < ?
                                   AND b.settlement_status <> 'CANCELED'
                                   AND COALESCE(b.player_kind_snapshot, a.player_kind)='NORMAL'), 0) total_flow
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

        Map<Long, BigDecimal> openingBalances = new HashMap<>();
        Map<Long, Boolean> hasParticipants = new HashMap<>();
        Map<Long, Boolean> unknownOpening = new HashMap<>();
        for (ScoreFlowPlayerAccumulator participant : reportParticipants(range, scopedSubAccountId)) {
            hasParticipants.put(participant.machineId(), true);
            BigDecimal opening = balanceAtRange(participant, range).opening();
            if (opening == null) unknownOpening.put(participant.machineId(), true);
            else openingBalances.merge(participant.machineId(), opening, BigDecimal::add);
        }

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
            boolean hasEvents = hasParticipants.getOrDefault(row.machineId(), false);
            BigDecimal openingPlayers = openingBalances.getOrDefault(row.machineId(), BigDecimal.ZERO);
            BigDecimal totalRemainingForMachine = hasEvents
                    ? (unknownOpening.getOrDefault(row.machineId(), false) ? null : openingPlayers)
                    : row.score();
            return new MachineProfitRow(row.machineId(), row.machineCode(), row.machineName(), row.groupId(),
                    row.groupUsername(), row.score(), openingPlayers, totalRemainingForMachine, row.totalFlow(),
                    flow.singleFlow(), flow.doubleFlow(), flow.profit(), flow.fanShui(), flow.doubleFanShui(),
                    movement.up(), movement.down(), rebateMode, effectiveRebate);
        }).toList();
        BigDecimal totalRemaining = rows.stream().anyMatch(row -> row.totalRemaining() == null)
                ? null : rows.stream().map(MachineProfitRow::totalRemaining).reduce(BigDecimal.ZERO, BigDecimal::add);
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
                   AND b.settlement_status <> 'CANCELED'
                   AND COALESCE(b.player_kind_snapshot, a.player_kind)='NORMAL'
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

    private List<ScoreFlowPlayerAccumulator> reportParticipants(BusinessRange range, Long subAccountId) {
        StringBuilder sql = new StringBuilder("""
                SELECT player_key, MAX(user_id) user_id, MAX(account_id) account_id,
                       MAX(member_code) member_code, MAX(player_name) player_name,
                       machine_id, MAX(machine_name) machine_name, MAX(sub_account) sub_account,
                       MAX(rebate_mode) rebate_mode
                  FROM (
                        SELECT %s player_key, a.sys_user_id user_id, a.id account_id,
                               COALESCE(a.member_code,b.player_code_snapshot) member_code,
                               COALESCE(a.display_name,b.player_name_snapshot) player_name,
                               ag.id machine_id, ag.display_name machine_name, g.username sub_account,
                               COALESCE(ag.rebate_mode,'SINGLE') rebate_mode
                          FROM game_bet b LEFT JOIN demo_user_account a ON a.id=b.user_id
                          JOIN agent ag ON ag.id=COALESCE(a.agent_id,b.agent_id_snapshot)
                          LEFT JOIN agent_group g ON g.id=ag.group_id
                         WHERE COALESCE(b.player_kind_snapshot,a.player_kind)='NORMAL'
                           AND b.settlement_status <> 'CANCELED' AND b.created_at >= ? AND b.created_at < ?
                           AND ag.system_owned=FALSE
                """.formatted(playerKey("a", "b")));
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        appendGroupFilter(sql, args, subAccountId, "g.id");
        sql.append("""
                        UNION ALL
                        SELECT %s player_key, a.sys_user_id user_id, a.id account_id,
                               COALESCE(a.member_code,l.player_code_snapshot) member_code,
                               COALESCE(a.display_name,l.player_name_snapshot) player_name,
                               ag.id machine_id, ag.display_name machine_name, g.username sub_account,
                               COALESCE(ag.rebate_mode,'SINGLE') rebate_mode
                          FROM demo_balance_ledger l LEFT JOIN demo_user_account a ON a.id=l.user_id
                          JOIN agent ag ON ag.id=COALESCE(a.agent_id,l.agent_id_snapshot)
                          LEFT JOIN agent_group g ON g.id=ag.group_id
                         WHERE COALESCE(l.player_kind_snapshot,a.player_kind)='NORMAL'
                           AND l.operation_type IN ('ADMIN_GRANT','ADMIN_ADJUST')
                           AND l.created_at >= ? AND l.created_at < ? AND ag.system_owned=FALSE
                """.formatted(playerKey("a", "l")));
        args.add(Timestamp.from(range.from()));
        args.add(Timestamp.from(range.to()));
        appendGroupFilter(sql, args, subAccountId, "g.id");
        sql.append(" ) event_rows GROUP BY player_key,machine_id");
        return jdbc.query(sql.toString(), (rs, n) -> new ScoreFlowPlayerAccumulator(
                rs.getString("player_key"), nullableLong(rs, "user_id"), nullableLong(rs, "account_id"),
                rs.getString("member_code"), rs.getString("player_name"), rs.getLong("machine_id"),
                rs.getString("machine_name"), rs.getString("sub_account"), rs.getString("rebate_mode")),
                args.toArray());
    }

    private void scanScoreFlowBets(BusinessRange range, Long subAccountId,
                                   Map<Long, ScoreFlowMetricAccumulator> machineMetrics,
                                   Map<String, ScoreFlowPlayerAccumulator> pagePeople) {
        String key = playerKey("a", "b");
        StringBuilder sql = new StringBuilder("""
                SELECT %s player_key, COALESCE(a.agent_id,b.agent_id_snapshot) agent_id,
                       b.play_type, b.parameters_text, b.stake, b.odds_snapshot, b.net_profit,
                       b.settlement_status, b.rebate_rate_snapshot, b.special_rebate_rate_snapshot,
                       (SELECT MIN(s.unit_stake) FROM game_bet_selection s WHERE s.bet_id=b.id) selection_unit_stake
                  FROM game_bet b
                  LEFT JOIN demo_user_account a ON a.id=b.user_id
                  JOIN agent ag ON ag.id=COALESCE(a.agent_id,b.agent_id_snapshot)
                 WHERE COALESCE(b.player_kind_snapshot,a.player_kind)='NORMAL'
                   AND b.settlement_status <> 'CANCELED'
                   AND b.created_at >= ? AND b.created_at < ?
                   AND ag.system_owned=FALSE
                """.formatted(key));
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        appendGroupFilter(sql, args, subAccountId, "ag.group_id");
        jdbc.query(sql.toString(), rs -> {
            String playerKey = rs.getString("player_key");
            long machineId = rs.getLong("agent_id");
            com.xupan.server.game.domain.PlayType type =
                    com.xupan.server.game.domain.PlayType.valueOf(rs.getString("play_type"));
            BigDecimal stake = rs.getBigDecimal("stake");
            BigDecimal unitStake = type == com.xupan.server.game.domain.PlayType.SPECIAL
                    ? rs.getBigDecimal("selection_unit_stake") : stake;
            BetFlowMetricsCalculator.Metrics metric = BetFlowMetricsCalculator.calculate(type,
                    rs.getString("parameters_text"), stake, rs.getBigDecimal("odds_snapshot"),
                    rs.getBigDecimal("net_profit"), rs.getString("settlement_status"),
                    rs.getBigDecimal("rebate_rate_snapshot"), rs.getBigDecimal("special_rebate_rate_snapshot"),
                    unitStake);
            ScoreFlowMetricAccumulator machine = machineMetrics.computeIfAbsent(
                    machineId, ignored -> new ScoreFlowMetricAccumulator());
            machine.turnover = machine.turnover.add(stake);
            machine.add(metric);
            ScoreFlowPlayerAccumulator player = pagePeople.get(playerKey);
            if (player != null) player.addBetMetric(stake, metric);
        }, args.toArray());
    }

    private void scanScoreFlowPointOperations(BusinessRange range, Long subAccountId,
                                              Map<Long, ScoreFlowMetricAccumulator> machineMetrics,
                                              Map<String, ScoreFlowPlayerAccumulator> pagePeople) {
        String key = playerKey("a", "l");
        StringBuilder sql = new StringBuilder("""
                SELECT %s player_key, COALESCE(a.agent_id,l.agent_id_snapshot) agent_id, l.amount
                  FROM demo_balance_ledger l
                  LEFT JOIN demo_user_account a ON a.id=l.user_id
                  JOIN agent ag ON ag.id=COALESCE(a.agent_id,l.agent_id_snapshot)
                 WHERE COALESCE(l.player_kind_snapshot,a.player_kind)='NORMAL'
                   AND l.operation_type IN ('ADMIN_GRANT','ADMIN_ADJUST')
                   AND l.created_at >= ? AND l.created_at < ?
                   AND ag.system_owned=FALSE
                """.formatted(key));
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        appendGroupFilter(sql, args, subAccountId, "ag.group_id");
        jdbc.query(sql.toString(), rs -> {
            String playerKey = rs.getString("player_key");
            long machineId = rs.getLong("agent_id");
            BigDecimal amount = rs.getBigDecimal("amount");
            ScoreFlowMetricAccumulator metrics = machineMetrics.computeIfAbsent(
                    machineId, ignored -> new ScoreFlowMetricAccumulator());
            if (amount.signum() > 0) metrics.up = metrics.up.add(amount);
            else metrics.down = metrics.down.add(amount.abs());
            ScoreFlowPlayerAccumulator player = pagePeople.get(playerKey);
            if (player != null) player.addPointMetric(amount);
        }, args.toArray());
    }

    private void loadScoreFlowDetails(List<ScoreFlowPlayerAccumulator> people, BusinessRange range) {
        if (people.isEmpty()) return;
        String betKey = playerKey("a", "b");
        String ledgerKey = playerKey("a", "l");
        String playerScopes = String.join(" OR ", people.stream()
                .map(ignored -> "(" + betKey + "=? AND COALESCE(a.agent_id,b.agent_id_snapshot)=?)")
                .toList());
        List<Object> betArgs = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        appendPlayerScopes(betArgs, people);
        List<ScoreFlowBetDetail> bets = jdbc.query("""
                SELECT id,player_key,machine_id,issue_number,play_type,parameters_text,stake,
                       net_profit,settlement_status,created_at
                  FROM (
                        SELECT %s player_key,COALESCE(a.agent_id,b.agent_id_snapshot) machine_id,
                               b.id,b.issue_number,b.play_type,b.parameters_text,b.stake,b.net_profit,
                               b.settlement_status,b.created_at,
                               ROW_NUMBER() OVER (
                                   PARTITION BY %s,COALESCE(a.agent_id,b.agent_id_snapshot)
                                   ORDER BY b.created_at DESC,b.id DESC
                               ) row_no
                          FROM game_bet b LEFT JOIN demo_user_account a ON a.id=b.user_id
                          JOIN agent ag ON ag.id=COALESCE(a.agent_id,b.agent_id_snapshot)
                         WHERE COALESCE(b.player_kind_snapshot,a.player_kind)='NORMAL'
                           AND b.settlement_status <> 'CANCELED'
                           AND b.created_at >= ? AND b.created_at < ?
                           AND (%s)
                       ) ranked_bets
                 WHERE row_no <= %d
                 ORDER BY player_key,machine_id,created_at DESC,id DESC
                """.formatted(betKey, betKey, playerScopes, DETAIL_LIMIT), (rs, n) ->
                new ScoreFlowBetDetail(rs.getString("player_key"), rs.getLong("machine_id"),
                        rs.getLong("id"), rs.getString("issue_number"), rs.getString("play_type"),
                        rs.getString("parameters_text"), rs.getBigDecimal("stake"),
                        rs.getBigDecimal("net_profit"), rs.getString("settlement_status"),
                        instant(rs.getTimestamp("created_at"))), betArgs.toArray());

        String operationScopes = String.join(" OR ", people.stream()
                .map(ignored -> "(" + ledgerKey + "=? AND COALESCE(a.agent_id,l.agent_id_snapshot)=?)")
                .toList());
        List<Object> operationArgs = new ArrayList<>(List.of(Timestamp.from(range.from()), Timestamp.from(range.to())));
        appendPlayerScopes(operationArgs, people);
        List<ScoreFlowPointOperation> operations = jdbc.query("""
                SELECT id,player_key,machine_id,operation_type,amount,balance_before,balance_after,reason,created_at
                  FROM (
                        SELECT %s player_key,COALESCE(a.agent_id,l.agent_id_snapshot) machine_id,
                               l.id,l.operation_type,l.amount,l.balance_before,l.balance_after,l.reason,l.created_at,
                               ROW_NUMBER() OVER (
                                   PARTITION BY %s,COALESCE(a.agent_id,l.agent_id_snapshot)
                                   ORDER BY l.created_at DESC,l.id DESC
                               ) row_no
                          FROM demo_balance_ledger l LEFT JOIN demo_user_account a ON a.id=l.user_id
                          JOIN agent ag ON ag.id=COALESCE(a.agent_id,l.agent_id_snapshot)
                         WHERE COALESCE(l.player_kind_snapshot,a.player_kind)='NORMAL'
                           AND l.operation_type IN ('ADMIN_GRANT','ADMIN_ADJUST')
                           AND l.created_at >= ? AND l.created_at < ?
                           AND (%s)
                       ) ranked_operations
                 WHERE row_no <= %d
                 ORDER BY player_key,machine_id,created_at DESC,id DESC
                """.formatted(ledgerKey, ledgerKey, operationScopes, DETAIL_LIMIT), (rs, n) ->
                new ScoreFlowPointOperation(rs.getString("player_key"), rs.getLong("machine_id"),
                        rs.getLong("id"), rs.getString("operation_type"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_before"), rs.getBigDecimal("balance_after"),
                        rs.getString("reason"), instant(rs.getTimestamp("created_at"))), operationArgs.toArray());

        Map<String, List<ScoreFlowBetDetail>> betsByPerson = new HashMap<>();
        bets.forEach(bet -> betsByPerson.computeIfAbsent(personKey(bet.playerKey(), bet.machineId()),
                ignored -> new ArrayList<>()).add(bet));
        Map<String, List<ScoreFlowPointOperation>> operationsByPerson = new HashMap<>();
        operations.forEach(operation -> operationsByPerson.computeIfAbsent(
                personKey(operation.playerKey(), operation.machineId()), ignored -> new ArrayList<>()).add(operation));
        for (ScoreFlowPlayerAccumulator person : people) {
            String key = personKey(person.playerKey(), person.machineId());
            person.setDetails(betsByPerson.getOrDefault(key, List.of()),
                    operationsByPerson.getOrDefault(key, List.of()));
        }
    }

    private static void appendPlayerScopes(List<Object> args, List<ScoreFlowPlayerAccumulator> people) {
        for (ScoreFlowPlayerAccumulator person : people) {
            args.add(person.playerKey());
            args.add(person.machineId());
        }
    }

    private static String personKey(String playerKey, long machineId) {
        return playerKey + ":" + machineId;
    }

    private BalanceSnapshot balanceAtRange(ScoreFlowPlayerAccumulator person, BusinessRange range) {
        String key = playerKey("a", "l");
        String subject = person.accountId() == null ? key + "=?" : "l.user_id=?";
        Object identity = person.accountId() == null ? person.playerKey() : person.accountId();
        String scope = " AND COALESCE(a.agent_id,l.agent_id_snapshot)=?";
        String sql = """
                SELECT COALESCE(
                           (SELECT l.balance_after FROM demo_balance_ledger l LEFT JOIN demo_user_account a ON a.id=l.user_id
                             WHERE %s%s AND l.created_at < ? ORDER BY l.created_at DESC,l.id DESC LIMIT 1),
                           (SELECT l.balance_before FROM demo_balance_ledger l LEFT JOIN demo_user_account a ON a.id=l.user_id
                             WHERE %s%s AND l.created_at >= ? AND l.created_at < ? ORDER BY l.created_at,l.id LIMIT 1)) opening,
                       COALESCE(
                           (SELECT l.balance_after FROM demo_balance_ledger l LEFT JOIN demo_user_account a ON a.id=l.user_id
                             WHERE %s%s AND l.created_at < ? ORDER BY l.created_at DESC,l.id DESC LIMIT 1),
                           (SELECT l.balance_after FROM demo_balance_ledger l LEFT JOIN demo_user_account a ON a.id=l.user_id
                             WHERE %s%s AND l.created_at < ? ORDER BY l.created_at DESC,l.id DESC LIMIT 1)) closing
                """.formatted(subject, scope, subject, scope, subject, scope, subject, scope);
        return jdbc.queryForObject(sql, (rs, n) -> new BalanceSnapshot(rs.getBigDecimal("opening"),
                rs.getBigDecimal("closing")), identity, person.machineId(), Timestamp.from(range.from()),
                identity, person.machineId(), Timestamp.from(range.from()), Timestamp.from(range.to()),
                identity, person.machineId(), Timestamp.from(range.to()), identity, person.machineId(),
                Timestamp.from(range.from()));
    }

    private ScoreFlowSummary scoreFlowSummary(BusinessRange range, Long subAccountId,
                                               Map<Long, ScoreFlowMetricAccumulator> metrics,
                                               List<ScoreFlowPlayerAccumulator> participants) {
        BigDecimal opening = BigDecimal.ZERO;
        BigDecimal closing = BigDecimal.ZERO;
        boolean openingKnown = true;
        boolean closingKnown = true;
        for (ScoreFlowPlayerAccumulator person : participants) {
            BalanceSnapshot snapshot = balanceAtRange(person, range);
            if (snapshot.opening() == null) openingKnown = false;
            else opening = opening.add(snapshot.opening());
            if (snapshot.closing() == null) closingKnown = false;
            else closing = closing.add(snapshot.closing());
        }
        ScoreFlowMetricAccumulator all = new ScoreFlowMetricAccumulator();
        metrics.values().forEach(all::add);
        StringBuilder machinesSql = new StringBuilder("SELECT id,COALESCE(rebate_mode,'SINGLE') rebate_mode FROM agent WHERE system_owned=FALSE AND deleted_at IS NULL");
        List<Object> machineArgs = new ArrayList<>();
        if (subAccountId != null) { machinesSql.append(" AND group_id=?"); machineArgs.add(subAccountId); }
        BigDecimal effectiveRebate = BigDecimal.ZERO;
        for (Map.Entry<Long, String> entry : jdbc.query(machinesSql.toString(),
                (rs, n) -> Map.entry(rs.getLong("id"), rs.getString("rebate_mode")), machineArgs.toArray())) {
            ScoreFlowMetricAccumulator value = metrics.get(entry.getKey());
            if (value != null) effectiveRebate = effectiveRebate.add(
                    MachineBettingConfig.DOUBLE.equals(normalizeRebateMode(entry.getValue()))
                            ? value.doubleRebate : value.singleRebate);
        }
        return new ScoreFlowSummary(openingKnown ? money(opening) : null,
                closingKnown ? money(closing) : null, money(all.turnover), money(all.singleFlow),
                money(all.doubleFlow), money(all.netProfit), money(all.singleRebate), money(all.doubleRebate),
                money(effectiveRebate), money(all.up), money(all.down), money(all.up.subtract(all.down)));
    }

    private static String playerKey(String accountAlias, String sourceAlias) {
        return "CASE WHEN " + accountAlias + ".id IS NOT NULL THEN CONCAT('A:'," + accountAlias + ".id) "
                + "ELSE CONCAT('S:',COALESCE(" + sourceAlias + ".agent_id_snapshot,0),':',"
                + "COALESCE(NULLIF(" + sourceAlias + ".player_code_snapshot,''),"
                + sourceAlias + ".player_name_snapshot,'')) END";
    }

    private static void appendGroupFilter(StringBuilder sql, List<Object> args, Long groupId, String column) {
        if (groupId != null) {
            sql.append(" AND ").append(column).append("=?");
            args.add(groupId);
        }
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
                   AND COALESCE(l.player_kind_snapshot, a.player_kind)='NORMAL'
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
    private record BalanceSnapshot(BigDecimal opening, BigDecimal closing) {}
    public record ScoreFlowBetDetail(String playerKey, long machineId, long id, String issueNumber,
                                     String playType, String parametersText, BigDecimal stake,
                                     BigDecimal netProfit, String settlementStatus, Instant createdAt) {}
    public record ScoreFlowPointOperation(String playerKey, long machineId, long id,
                                          String operationType, BigDecimal amount,
                                          BigDecimal balanceBefore, BigDecimal balanceAfter,
                                          String reason, Instant createdAt) {}
    public record ScoreFlowPlayerRow(Long userId, Long accountId, String memberCode, String playerName,
                                     long machineId, String machineName, String subAccount,
                                     String rebateMode, BigDecimal openingBalance, BigDecimal closingBalance,
                                     BigDecimal turnover, BigDecimal singleFlow, BigDecimal doubleFlow,
                                     BigDecimal netProfit, BigDecimal singleRebate, BigDecimal doubleRebate,
                                     BigDecimal effectiveRebate, BigDecimal totalUp, BigDecimal totalDown,
                                     BigDecimal upDown, List<ScoreFlowBetDetail> bets,
                                     List<ScoreFlowPointOperation> pointOperations) {}
    public record ScoreFlowSummary(BigDecimal openingBalance, BigDecimal closingBalance, BigDecimal turnover,
                                   BigDecimal singleFlow, BigDecimal doubleFlow, BigDecimal netProfit,
                                   BigDecimal singleRebate, BigDecimal doubleRebate, BigDecimal effectiveRebate,
                                   BigDecimal totalUp, BigDecimal totalDown, BigDecimal upDown) {}
    public record ScoreFlowPage(List<ScoreFlowPlayerRow> items, int page, int pageSize, long total,
                                ScoreFlowSummary summary) {}
    public record MachineProfitRow(long machineId, String machineCode, String machineName, Long groupId,
                                   String groupUsername, BigDecimal score, BigDecimal playerBalance,
                                   BigDecimal totalRemaining, BigDecimal totalFlow, BigDecimal totalSingleFlow,
                                   BigDecimal totalDoubleFlow,
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

    private static final class ScoreFlowMetricAccumulator {
        private BigDecimal turnover = BigDecimal.ZERO;
        private BigDecimal singleFlow = BigDecimal.ZERO;
        private BigDecimal doubleFlow = BigDecimal.ZERO;
        private BigDecimal netProfit = BigDecimal.ZERO;
        private BigDecimal singleRebate = BigDecimal.ZERO;
        private BigDecimal doubleRebate = BigDecimal.ZERO;
        private BigDecimal up = BigDecimal.ZERO;
        private BigDecimal down = BigDecimal.ZERO;

        void add(BetFlowMetricsCalculator.Metrics metric) {
            singleFlow = singleFlow.add(metric.singleFlow());
            doubleFlow = doubleFlow.add(metric.doubleFlow());
            netProfit = netProfit.add(metric.profit());
            singleRebate = singleRebate.add(metric.fanShui());
            doubleRebate = doubleRebate.add(metric.doubleFanShui());
        }

        void add(ScoreFlowMetricAccumulator other) {
            turnover = turnover.add(other.turnover);
            singleFlow = singleFlow.add(other.singleFlow);
            doubleFlow = doubleFlow.add(other.doubleFlow);
            netProfit = netProfit.add(other.netProfit);
            singleRebate = singleRebate.add(other.singleRebate);
            doubleRebate = doubleRebate.add(other.doubleRebate);
            up = up.add(other.up);
            down = down.add(other.down);
        }
    }

    private static final class ScoreFlowPlayerAccumulator {
        private final String playerKey;
        private final Long userId;
        private final Long accountId;
        private final String memberCode;
        private final String playerName;
        private final long machineId;
        private final String machineName;
        private final String subAccount;
        private final String rebateMode;
        private BalanceSnapshot balances = new BalanceSnapshot(ZERO, ZERO);
        private BigDecimal turnover = BigDecimal.ZERO;
        private BigDecimal singleFlow = BigDecimal.ZERO;
        private BigDecimal doubleFlow = BigDecimal.ZERO;
        private BigDecimal netProfit = BigDecimal.ZERO;
        private BigDecimal singleRebate = BigDecimal.ZERO;
        private BigDecimal doubleRebate = BigDecimal.ZERO;
        private BigDecimal totalUp = BigDecimal.ZERO;
        private BigDecimal totalDown = BigDecimal.ZERO;
        private List<ScoreFlowBetDetail> bets = List.of();
        private List<ScoreFlowPointOperation> pointOperations = List.of();

        private ScoreFlowPlayerAccumulator(String playerKey, Long userId, Long accountId, String memberCode,
                                           String playerName, long machineId, String machineName,
                                           String subAccount, String rebateMode) {
            this.playerKey = playerKey;
            this.userId = userId;
            this.accountId = accountId;
            this.memberCode = memberCode;
            this.playerName = playerName;
            this.machineId = machineId;
            this.machineName = machineName;
            this.subAccount = subAccount;
            this.rebateMode = normalizeRebateMode(rebateMode);
        }

        String playerKey() { return playerKey; }
        Long accountId() { return accountId; }
        long machineId() { return machineId; }
        void setBalances(BalanceSnapshot value) { balances = value; }
        void addBetMetric(BigDecimal stake, BetFlowMetricsCalculator.Metrics value) {
            turnover = turnover.add(stake);
            singleFlow = singleFlow.add(value.singleFlow());
            doubleFlow = doubleFlow.add(value.doubleFlow());
            netProfit = netProfit.add(value.profit());
            singleRebate = singleRebate.add(value.fanShui());
            doubleRebate = doubleRebate.add(value.doubleFanShui());
        }
        void addPointMetric(BigDecimal amount) {
            if (amount.signum() > 0) totalUp = totalUp.add(amount);
            else totalDown = totalDown.add(amount.abs());
        }
        void setDetails(List<ScoreFlowBetDetail> betDetails, List<ScoreFlowPointOperation> operations) {
            bets = betDetails.stream().sorted(Comparator.comparing(ScoreFlowBetDetail::createdAt)
                    .thenComparingLong(ScoreFlowBetDetail::id)).toList();
            pointOperations = operations.stream().sorted(Comparator.comparing(ScoreFlowPointOperation::createdAt)
                    .thenComparingLong(ScoreFlowPointOperation::id)).toList();
        }
        ScoreFlowPlayerRow toRow() {
            BigDecimal effectiveRebate = MachineBettingConfig.DOUBLE.equals(rebateMode) ? doubleRebate : singleRebate;
            return new ScoreFlowPlayerRow(userId, accountId, memberCode, playerName, machineId, machineName,
                    subAccount, rebateMode, balances.opening() == null ? null : money(balances.opening()),
                    balances.closing() == null ? null : money(balances.closing()), money(turnover),
                    money(singleFlow), money(doubleFlow), money(netProfit), money(singleRebate), money(doubleRebate),
                    money(effectiveRebate), money(totalUp), money(totalDown), money(totalUp.subtract(totalDown)),
                    bets, pointOperations);
        }
    }
}




