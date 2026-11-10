package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.center.service.CenterNetProfitCalculator;
import com.xupan.server.game.domain.DrawRule;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.SettlementService;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class PlatformBettingPlayStatsService {

    private static final String READ_PERMISSION = "BETTING_PLAY_STATS_READ";
    private static final String PRESET_PERMISSION = "BETTING_DRAW_PRESET_MANAGE";
    private static final BigDecimal MIN_RATIO = new BigDecimal("0.01");
    private static final BigDecimal MAX_RATIO = new BigDecimal("100.00");

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final CenterNetProfitCalculator centerNetProfitCalculator;
    private final SecureRandom drawRandom = new SecureRandom();

    public PlatformBettingPlayStatsService(JdbcTemplate jdbc, PermissionService permissionService,
                                           CenterNetProfitCalculator centerNetProfitCalculator) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.centerNetProfitCalculator = centerNetProfitCalculator;
    }

    @Transactional(readOnly = true)
    public CurrentStats current(String gameCode, long operator) {
        require(operator, READ_PERMISSION);
        String normalizedGameCode = normalizeGameCode(gameCode);
        GameInfo game = jdbc.query("""
                SELECT display_name, next_issue_ball_count
                  FROM game_definition
                 WHERE game_code = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                """, rs -> rs.next() ? new GameInfo(rs.getString("display_name"),
                rs.getInt("next_issue_ball_count")) : null,
                normalizedGameCode);
        if (game == null) {
            throw BusinessException.notFound("BETTING_PLAY_STATS_GAME_NOT_FOUND", "未找到可查看的彩种");
        }
        if (game.nextIssueBallCount() < 1 || game.nextIssueBallCount() > 8) {
            throw new IllegalStateException("彩种球数配置无效");
        }

        CurrentIssue issue = currentIssue(normalizedGameCode);
        Instant now = Instant.now();
        if (issue == null) {
            return new CurrentStats(normalizedGameCode, game.displayName(), null, "NONE", game.nextIssueBallCount(),
                    0, null, null, now, List.of());
        }

        List<PlayRow> playRows = jdbc.query("""
                SELECT b.ball_number, b.play_type, b.parameters_text, COUNT(DISTINCT c.id) AS order_count,
                       COALESCE(SUM(c.amount), 0) AS total_stake
                  FROM game_bet b
                  JOIN center_order c ON c.local_bet_id = b.id
                  JOIN report_upstream_order r ON r.bet_id = b.id
                   AND r.report_type = 'CENTER' AND r.center_agent_id = c.center_agent_id
                   AND r.id = (SELECT MAX(r2.id) FROM report_upstream_order r2
                                WHERE r2.bet_id = b.id AND r2.report_type = 'CENTER'
                                  AND r2.center_agent_id = c.center_agent_id)
                 WHERE b.game_code = ? AND b.issue_number = ? AND b.settlement_status = 'PENDING'
                   AND b.player_kind_snapshot = 'NORMAL' AND c.status = 'ACCEPTED'
                   AND r.status = 'ACCEPTED' AND c.agent_order_no = CONCAT('BET-', b.id)
                   AND c.game_code = b.game_code AND c.issue_number = b.issue_number
                   AND b.ball_number BETWEEN 1 AND ?
                 GROUP BY b.ball_number, b.play_type, b.parameters_text
                """, (rs, rowNum) -> new PlayRow(rs.getInt("ball_number"), rs.getString("play_type"),
                rs.getString("parameters_text"),
                rs.getLong("order_count"), money(rs.getBigDecimal("total_stake"))),
                normalizedGameCode, issue.issueNumber(), issue.ballCount());

        Map<Integer, Map<String, PlayAccumulator>> playsByBall = new LinkedHashMap<>();
        for (int ballNumber = 1; ballNumber <= issue.ballCount(); ballNumber++) {
            playsByBall.put(ballNumber, new LinkedHashMap<>());
        }
        for (PlayRow row : playRows) {
            Map<String, PlayAccumulator> ballPlays = playsByBall.get(row.ballNumber());
            if (ballPlays != null) {
                RuleDescriptor descriptor = ruleDescriptor(row.playType(), row.parametersText());
                PlayAccumulator accumulator = ballPlays.computeIfAbsent(descriptor.ruleKey(),
                        ignored -> new PlayAccumulator(descriptor));
                accumulator.orderCount += row.orderCount();
                accumulator.totalStake = accumulator.totalStake.add(row.totalStake());
            }
        }

        Comparator<PlayStats> ranking = Comparator.comparingLong(PlayStats::orderCount).reversed()
                .thenComparing(PlayStats::totalStake, Comparator.reverseOrder())
                .thenComparing(PlayStats::ruleKey);
        List<BallStats> balls = new ArrayList<>();
        for (int ballNumber = 1; ballNumber <= issue.ballCount(); ballNumber++) {
            List<PlayStats> ranked = playsByBall.get(ballNumber).values().stream()
                    .map(PlayAccumulator::toStats).collect(Collectors.toCollection(ArrayList::new));
            ranked.sort(ranking);
            long orderCount = ranked.stream().mapToLong(PlayStats::orderCount).sum();
            BigDecimal totalStake = ranked.stream().map(PlayStats::totalStake)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            balls.add(new BallStats(ballNumber, issue.numbers().get(ballNumber - 1),
                    orderCount, totalStake, List.copyOf(ranked)));
        }
        return new CurrentStats(normalizedGameCode, game.displayName(), issue.issueNumber(), issue.phase(),
                issue.ballCount(), issue.drawResultVersion(),
                issue.bettingEndsAt(), issue.drawEndsAt(), now, List.copyOf(balls));
    }

    @Transactional
    public ControlSummary control(String expectedGameCode, String expectedIssueNumber,
                                  int expectedDrawResultVersion, BigDecimal ratioPercent,
                                  String idempotencyKey, long operator) {
        require(operator, PRESET_PERMISSION);
        String gameCode = normalizeGameCode(expectedGameCode);
        String issueNumber = requiredIssueNumber(expectedIssueNumber);
        BigDecimal ratio = validateRatio(ratioPercent);
        String key = requiredIdempotencyKey(idempotencyKey);
        String requestDigest = sha256(gameCode + "|" + issueNumber + "|"
                + expectedDrawResultVersion + "|" + ratio.toPlainString());

        long operationId;
        try {
            jdbc.update("""
                    INSERT INTO betting_play_control_operation
                        (idempotency_key, request_digest, game_code, issue_number,
                         expected_draw_result_version, ratio_percent, operator_user_id, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PROCESSING')
                    """, key, requestDigest, gameCode, issueNumber, expectedDrawResultVersion, ratio, operator);
            Long insertedId = jdbc.queryForObject(
                    "SELECT id FROM betting_play_control_operation WHERE idempotency_key = ?", Long.class, key);
            if (insertedId == null) throw new IllegalStateException("控盘操作审计记录缺少主键");
            operationId = insertedId;
        } catch (DuplicateKeyException duplicate) {
            ControlSummary prior = findControlByKey(key);
            if (prior == null || !prior.requestDigest().equals(requestDigest)) {
                throw BusinessException.conflict("BETTING_PLAY_CONTROL_IDEMPOTENCY_CONFLICT",
                        "控盘幂等键已用于其他请求");
            }
            if (!"SUCCEEDED".equals(prior.status())) {
                throw BusinessException.conflict("BETTING_PLAY_CONTROL_IN_PROGRESS", "相同控盘请求仍在处理中");
            }
            return prior;
        }

        CurrentIssue issue = currentIssueForUpdate(gameCode);
        if (issue == null) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_STALE_ISSUE", "当前没有可控盘的活动期");
        }
        if (!issue.issueNumber().equals(issueNumber)) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_STALE_ISSUE", "当前期号已变化，请刷新后重试");
        }
        if (issue.drawResultVersion() != expectedDrawResultVersion) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_STALE_VERSION", "开奖结果预设已变化，请刷新后重试");
        }
        requireControlWindow(issue);

        List<ControlOrder> orders = eligibleControlOrders(issue, true);
        if (orders.isEmpty()) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_NO_ORDERS", "当前期没有中心已接受的普通玩家订单");
        }
        for (ControlOrder order : orders) validateControlOrder(order, issue);

        BigDecimal totalStake = orders.stream().map(ControlOrder::amount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add).setScale(2);
        BigDecimal target = totalStake.multiply(ratio).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        int maxNumber = DrawRule.maxNumber(gameCode, issue.drawRuleVersion());
        List<List<BigDecimal>> profits = new ArrayList<>(issue.ballCount());
        try {
            for (int ball = 1; ball <= issue.ballCount(); ball++) {
                List<BigDecimal> perNumber = new ArrayList<>(maxNumber);
                for (int number = 1; number <= maxNumber; number++) {
                    BigDecimal profit = BigDecimal.ZERO.setScale(2);
                    for (ControlOrder order : orders) {
                        if (order.ballNumber() == ball) {
                            profit = profit.add(centerNetProfitCalculator.forOrder(gameCode,
                                    order.centerPlayType(), order.centerPlayText(), order.amount(),
                                    order.centerOdds(), order.machineOddsRate(), number, issue.drawRuleVersion()));
                        }
                    }
                    perNumber.add(profit.setScale(2));
                }
                profits.add(List.copyOf(perNumber));
            }
        } catch (IllegalArgumentException invalidSnapshot) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_SETTLEMENT_SNAPSHOT_INVALID",
                    "存在无法按正式结算规则还原的中心订单，未修改开奖结果预设");
        }

        boolean distinctNumbers = DrawRule.requiresDistinctNumbers(gameCode, issue.drawRuleVersion());
        Optional<CenterProfitDrawOptimizer.Result> maximum = CenterProfitDrawOptimizer.optimize(
                profits, target, distinctNumbers);
        if (maximum.isEmpty()) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_TARGET_UNREACHABLE",
                    "当前中心已接受订单没有能达到抽成目标的合法开奖结果");
        }
        List<Integer> previousControlDraw = lastSuccessfulControlDraw(issue);
        Optional<CenterProfitDrawOptimizer.Result> sampled = CenterProfitDrawOptimizer.sampleAtLeastTarget(
                profits, target, distinctNumbers, previousControlDraw, drawRandom);
        if (sampled.isEmpty()) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_NO_ALTERNATIVE",
                    "当前预设已经达标，且没有其他能达到目标的合法开奖结果；原预设保持不变");
        }
        List<Integer> numbers = sampled.get().numbers();
        try {
            DrawRule.validateNumbers(gameCode, issue.drawRuleVersion(), issue.ballCount(), numbers);
        } catch (IllegalArgumentException invalidNumbers) {
            throw new IllegalStateException("控盘求解器返回不符合期次规则的号码", invalidNumbers);
        }
        BigDecimal predictedProfit = BigDecimal.ZERO.setScale(2);
        for (ControlOrder order : orders) {
            int number = numbers.get(order.ballNumber() - 1);
            predictedProfit = predictedProfit.add(centerNetProfitCalculator.forOrder(gameCode,
                    order.centerPlayType(), order.centerPlayText(), order.amount(), order.centerOdds(),
                    order.machineOddsRate(), number, issue.drawRuleVersion()));
        }
        predictedProfit = predictedProfit.setScale(2);
        if (predictedProfit.compareTo(target) < 0
                || predictedProfit.compareTo(sampled.get().centerNetProfit()) != 0) {
            throw new IllegalStateException("控盘结果的正式结算净赢与收益矩阵不一致");
        }
        requireControlWindow(issue);

        List<Object> updateArgs = new ArrayList<>();
        StringBuilder update = new StringBuilder("UPDATE game_issue SET ");
        for (int ball = 1; ball <= 8; ball++) {
            if (ball > 1) update.append(", ");
            update.append("number_").append(ball).append(" = ?");
            updateArgs.add(ball <= numbers.size() ? numbers.get(ball - 1) : null);
        }
        int resultVersion = issue.drawResultVersion() + 1;
        update.append(", draw_result_version = ?, updated_at = CURRENT_TIMESTAMP ")
                .append("WHERE game_code = ? AND issue_number = ? AND phase IN ('BETTING', 'DRAWING') ")
                .append("AND draw_confirmed_at IS NULL AND draw_result_version = ?");
        updateArgs.add(resultVersion);
        updateArgs.add(gameCode);
        updateArgs.add(issueNumber);
        updateArgs.add(expectedDrawResultVersion);
        if (jdbc.update(update.toString(), updateArgs.toArray()) != 1) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_STALE_VERSION", "期次已变化，控盘结果未写入");
        }

        String inputDigest = digestOrders(orders);
        String numbersText = numbers.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("""
                UPDATE betting_play_control_operation
                   SET input_digest = ?, result_version = ?, eligible_order_count = ?, total_stake = ?,
                       target_center_profit = ?, numbers_text = ?, predicted_center_profit = ?, status = 'SUCCEEDED'
                 WHERE id = ? AND status = 'PROCESSING'
                """, inputDigest, resultVersion, orders.size(), totalStake, target, numbersText,
                predictedProfit, operationId);
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'POST', ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, PRESET_PERMISSION, "/api/admin/betting-play-stats/current/control",
                gameCode + ":" + issueNumber,
                "orders=" + orders.size() + ",stake=" + totalStake.toPlainString()
                        + ",ratio=" + ratio.toPlainString() + ",target=" + target.toPlainString()
                        + ",numbers=" + numbersText + ",centerNet=" + predictedProfit.toPlainString());
        return new ControlSummary(operationId, key, requestDigest, "SUCCEEDED", gameCode, issueNumber,
                orders.size(), totalStake, ratio, target, numbers, predictedProfit, resultVersion);
    }

    private ControlSummary findControlByKey(String key) {
        return jdbc.query("""
                SELECT id, idempotency_key, request_digest, status, game_code, issue_number,
                       eligible_order_count, total_stake, ratio_percent, target_center_profit,
                       numbers_text, predicted_center_profit, result_version
                  FROM betting_play_control_operation WHERE idempotency_key = ?
                """, rs -> rs.next() ? new ControlSummary(rs.getLong("id"), rs.getString("idempotency_key"),
                rs.getString("request_digest"), rs.getString("status"), rs.getString("game_code"),
                rs.getString("issue_number"), rs.getLong("eligible_order_count"),
                rs.getBigDecimal("total_stake"), rs.getBigDecimal("ratio_percent"),
                rs.getBigDecimal("target_center_profit"), parseNumbers(rs.getString("numbers_text")),
                rs.getBigDecimal("predicted_center_profit"), rs.getObject("result_version", Integer.class)) : null,
                key);
    }

    private List<Integer> lastSuccessfulControlDraw(CurrentIssue issue) {
        List<ControlDrawSnapshot> rows = jdbc.query("""
                SELECT result_version, numbers_text
                  FROM betting_play_control_operation
                 WHERE game_code = ? AND issue_number = ? AND status = 'SUCCEEDED'
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new ControlDrawSnapshot(
                rs.getInt("result_version"), rs.getString("numbers_text")),
                issue.gameCode(), issue.issueNumber());
        if (rows.isEmpty() || rows.getFirst().resultVersion() != issue.drawResultVersion()) {
            return null;
        }
        List<Integer> previous = parseNumbers(rows.getFirst().numbersText());
        try {
            DrawRule.validateNumbers(issue.gameCode(), issue.drawRuleVersion(), issue.ballCount(), previous);
        } catch (IllegalArgumentException invalidAudit) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_PRIOR_RESULT_INVALID",
                    "上一组控盘结果与当前期规则不一致，请检查预设后重试");
        }
        return previous;
    }

    private List<ControlOrder> eligibleControlOrders(CurrentIssue issue, boolean lock) {
        String sql = """
                SELECT c.id AS center_order_id, b.id AS bet_id, c.center_agent_id,
                       b.ball_number, c.ball_number AS center_ball_number,
                       b.play_type AS local_play_type, b.parameters_text AS local_parameters,
                       b.stake AS local_stake, b.odds_snapshot AS local_odds,
                       c.game_code, c.issue_number, c.play_type AS center_play_type,
                       c.play_text AS center_play_text, c.amount, c.odds AS center_odds,
                       c.machine_odds_rate_snapshot
                  FROM center_order c
                  JOIN game_bet b ON b.id = c.local_bet_id
                  JOIN report_upstream_order r ON r.bet_id = b.id
                   AND r.report_type = 'CENTER' AND r.center_agent_id = c.center_agent_id
                   AND r.id = (SELECT MAX(r2.id) FROM report_upstream_order r2
                                WHERE r2.bet_id = b.id AND r2.report_type = 'CENTER'
                                  AND r2.center_agent_id = c.center_agent_id)
                 WHERE c.status = 'ACCEPTED' AND r.status = 'ACCEPTED'
                   AND c.game_code = ? AND c.issue_number = ?
                   AND b.game_code = c.game_code AND b.issue_number = c.issue_number
                   AND b.settlement_status = 'PENDING' AND b.player_kind_snapshot = 'NORMAL'
                   AND c.agent_order_no = CONCAT('BET-', b.id)
                   AND b.ball_number BETWEEN 1 AND ?
                 ORDER BY c.id
                """;
        if (lock) sql += " FOR UPDATE";
        return jdbc.query(sql, (rs, rowNum) -> new ControlOrder(rs.getLong("center_order_id"),
                rs.getLong("bet_id"), rs.getLong("center_agent_id"), rs.getInt("ball_number"),
                rs.getInt("center_ball_number"),
                rs.getString("local_play_type"), rs.getString("local_parameters"),
                rs.getBigDecimal("local_stake"), rs.getBigDecimal("local_odds"),
                rs.getString("game_code"), rs.getString("issue_number"), rs.getString("center_play_type"),
                rs.getString("center_play_text"), rs.getBigDecimal("amount"), rs.getBigDecimal("center_odds"),
                rs.getBigDecimal("machine_odds_rate_snapshot")), issue.gameCode(), issue.issueNumber(), issue.ballCount());
    }

    private void validateControlOrder(ControlOrder order, CurrentIssue issue) {
        if (order.ballNumber() < 1 || order.ballNumber() > issue.ballCount()
                || order.ballNumber() != order.centerBallNumber()
                || !order.gameCode().equals(issue.gameCode()) || !order.issueNumber().equals(issue.issueNumber())
                || !order.localPlayType().equalsIgnoreCase(order.centerPlayType())
                || order.localStake().compareTo(order.amount()) != 0
                || order.localOdds().compareTo(order.centerOdds()) != 0) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_ORDER_SNAPSHOT_INVALID",
                    "中心订单与本地注单快照不一致，未修改开奖结果预设");
        }
        String expectedParameters = order.localParameters() == null ? "" : order.localParameters().trim();
        String actualParameters;
        try {
            actualParameters = centerNetProfitCalculator.parseParameters(order.centerPlayText())
                    .stream().map(String::valueOf)
                    .collect(Collectors.joining(","));
        } catch (IllegalArgumentException malformed) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_ORDER_SNAPSHOT_INVALID",
                    "中心订单玩法参数无效，未修改开奖结果预设");
        }
        if (!expectedParameters.equals(actualParameters)) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_ORDER_SNAPSHOT_INVALID",
                    "中心订单与本地玩法参数不一致，未修改开奖结果预设");
        }
    }

    private static void requireControlWindow(CurrentIssue issue) {
        Instant now = Instant.now();
        if (issue.phase() == null || (!"BETTING".equals(issue.phase()) && !"DRAWING".equals(issue.phase()))
                || issue.drawEndsAt() == null || !now.isBefore(issue.drawEndsAt().minusSeconds(10))) {
            throw BusinessException.conflict("BETTING_PLAY_CONTROL_CLOSED", "开奖前 10 秒内不能计算或修改开奖结果预设");
        }
    }

    private static BigDecimal validateRatio(BigDecimal ratio) {
        if (ratio == null || ratio.scale() > 2 || ratio.compareTo(MIN_RATIO) < 0
                || ratio.compareTo(MAX_RATIO) > 0) {
            throw BusinessException.badRequest("BETTING_PLAY_CONTROL_RATIO_INVALID",
                    "抽成比例须在 0.01% 到 100.00% 之间且最多两位小数");
        }
        return ratio.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String requiredIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() > 128) {
            throw BusinessException.badRequest("BETTING_PLAY_CONTROL_IDEMPOTENCY_INVALID", "控盘幂等键不能为空且不能超过 128 个字符");
        }
        return key.trim();
    }

    private static String digestOrders(List<ControlOrder> orders) {
        StringBuilder canonical = new StringBuilder();
        for (ControlOrder order : orders) {
            canonical.append(order.centerOrderId()).append('|').append(order.betId()).append('|')
                    .append(order.centerAgentId()).append('|').append(order.ballNumber()).append('|')
                    .append(order.centerPlayType()).append('|').append(order.centerPlayText()).append('|')
                    .append(order.amount().toPlainString()).append('|').append(order.centerOdds().toPlainString())
                    .append('|').append(order.machineOddsRate() == null ? "" : order.machineOddsRate().toPlainString())
                    .append('\n');
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static List<Integer> parseNumbers(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(Integer::valueOf).toList();
    }

    @Transactional
    public PresetSummary preset(String expectedGameCode, String expectedIssueNumber,
                                int ballNumber, Integer number, long operator) {
        require(operator, PRESET_PERMISSION);
        String normalizedGameCode = normalizeGameCode(expectedGameCode);
        String normalizedIssueNumber = requiredIssueNumber(expectedIssueNumber);
        if (ballNumber < 1 || ballNumber > 8) {
            throw BusinessException.badRequest("BETTING_DRAW_PRESET_BALL_INVALID", "球位必须在 1 到 8 之间");
        }
        CurrentIssue issue = currentIssueForUpdate(normalizedGameCode);
        if (issue == null) {
            throw BusinessException.notFound("BETTING_DRAW_PRESET_ISSUE_NOT_FOUND", "当前没有可预设的开奖期次");
        }
        if (!issue.issueNumber().equals(normalizedIssueNumber)) {
            throw BusinessException.conflict("BETTING_DRAW_PRESET_STALE_ISSUE",
                    "当前期号已变化，请刷新后重新选择期次");
        }
        if (ballNumber > issue.ballCount()) {
            throw BusinessException.badRequest("BETTING_DRAW_PRESET_BALL_INVALID", "球位超出当前期球数范围");
        }
        int maxNumber = DrawRule.maxNumber(issue.gameCode(), issue.drawRuleVersion());
        if (number != null && (number < 1 || number > maxNumber)) {
            throw BusinessException.badRequest("BETTING_DRAW_PRESET_NUMBER_INVALID",
                    "预设号码必须在 1 到 " + maxNumber + " 之间");
        }
        if (number != null && DrawRule.requiresDistinctNumbers(issue.gameCode(), issue.drawRuleVersion())) {
            List<Integer> otherPresets = new ArrayList<>(issue.numbers());
            otherPresets.set(ballNumber - 1, null);
            if (otherPresets.contains(number)) {
                throw BusinessException.badRequest("BETTING_DRAW_PRESET_DUPLICATE",
                        "AU8 同一期的七个球位不能预设相同号码");
            }
        }
        Instant now = Instant.now();
        if (issue.drawEndsAt() == null || !now.isBefore(issue.drawEndsAt().minusSeconds(10))) {
            throw BusinessException.conflict("BETTING_DRAW_PRESET_CLOSED", "开奖前10秒内不能再修改开奖结果预设");
        }

        Integer previous = issue.numbers().get(ballNumber - 1);
        String column = "number_" + ballNumber;
        int updated = jdbc.update("UPDATE game_issue SET " + column + " = ?, "
                + "draw_result_version = draw_result_version + 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE game_code = ? AND issue_number = ? AND phase IN ('BETTING', 'DRAWING') "
                + "AND draw_confirmed_at IS NULL", number, issue.gameCode(), issue.issueNumber());
        if (updated != 1) {
            throw BusinessException.conflict("BETTING_DRAW_PRESET_CLOSED", "开奖结果已确认或期次已结束，不能修改预设");
        }
        audit(operator, issue, ballNumber, previous, number);
        return new PresetSummary(issue.gameCode(), issue.issueNumber(), issue.phase(), ballNumber,
                number, issue.drawEndsAt(), now);
    }

    private CurrentIssue currentIssue(String gameCode) {
        List<CurrentIssue> rows = jdbc.query("""
                SELECT game_code, issue_number, phase, ball_count, draw_rule_version, draw_result_version,
                       number_1, number_2, number_3, number_4, number_5, number_6, number_7, number_8,
                       betting_ends_at, draw_ends_at, draw_confirmed_at
                  FROM game_issue
                 WHERE game_code = ? AND phase IN ('BETTING', 'DRAWING')
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, rowNum) -> mapIssue(rs.getString("game_code"), rs.getString("issue_number"),
                rs.getString("phase"), rs.getInt("ball_count"), rs.getInt("draw_rule_version"),
                rs.getInt("draw_result_version"), readNumbers(rs, rs.getInt("ball_count")),
                instant(rs.getTimestamp("betting_ends_at")), instant(rs.getTimestamp("draw_ends_at")),
                rs.getTimestamp("draw_confirmed_at") != null), gameCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CurrentIssue currentIssueForUpdate(String gameCode) {
        List<CurrentIssue> rows = jdbc.query("""
                SELECT game_code, issue_number, phase, ball_count, draw_rule_version, draw_result_version,
                       number_1, number_2, number_3, number_4, number_5, number_6, number_7, number_8,
                       betting_ends_at, draw_ends_at, draw_confirmed_at
                  FROM game_issue
                 WHERE game_code = ? AND phase IN ('BETTING', 'DRAWING')
                 ORDER BY id DESC
                 LIMIT 1
                 FOR UPDATE
                """, (rs, rowNum) -> mapIssue(rs.getString("game_code"), rs.getString("issue_number"),
                rs.getString("phase"), rs.getInt("ball_count"), rs.getInt("draw_rule_version"),
                rs.getInt("draw_result_version"), readNumbers(rs, rs.getInt("ball_count")),
                instant(rs.getTimestamp("betting_ends_at")), instant(rs.getTimestamp("draw_ends_at")),
                rs.getTimestamp("draw_confirmed_at") != null), gameCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CurrentIssue mapIssue(String gameCode, String issueNumber, String phase, int ballCount,
                                  int drawRuleVersion, int drawResultVersion,
                                  List<Integer> numbers, Instant bettingEndsAt, Instant drawEndsAt,
                                  boolean confirmed) {
        if (ballCount < 1 || ballCount > 8 || numbers.size() != ballCount) {
            throw new IllegalStateException("当前期球数或开奖号码列不合法");
        }
        if (confirmed) {
            throw BusinessException.conflict("BETTING_DRAW_PRESET_CONFIRMED", "开奖结果已确认，不能修改预设");
        }
        return new CurrentIssue(gameCode, issueNumber, phase, ballCount, drawRuleVersion, drawResultVersion,
                numbers, bettingEndsAt, drawEndsAt);
    }

    private void audit(long operator, CurrentIssue issue, int ballNumber, Integer previous, Integer next) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'PUT', ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, PRESET_PERMISSION,
                "/api/admin/betting-play-stats/current/balls/" + ballNumber,
                issue.gameCode() + ":" + issue.issueNumber(),
                "ball=" + ballNumber + ",before=" + value(previous) + ",after=" + value(next));
    }

    private void require(long operator, String permission) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden("BETTING_PLAY_STATS_FORBIDDEN", "没有当前期玩法统计或开奖结果预设权限");
        }
    }

    private static String normalizeGameCode(String gameCode) {
        String normalized = gameCode == null || gameCode.isBlank()
                ? GameDataRepository.DEFAULT_GAME_CODE : gameCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 32 || !normalized.matches("[A-Z0-9_-]+")) {
            throw BusinessException.badRequest("BETTING_PLAY_STATS_GAME_INVALID", "彩种编码格式不正确");
        }
        return normalized;
    }

    private static String requiredIssueNumber(String issueNumber) {
        if (issueNumber == null || issueNumber.isBlank() || issueNumber.trim().length() > 64) {
            throw BusinessException.badRequest("BETTING_DRAW_PRESET_ISSUE_INVALID", "期号不能为空且不能超过 64 个字符");
        }
        return issueNumber.trim();
    }

    private static List<Integer> readNumbers(java.sql.ResultSet rs, int ballCount) throws java.sql.SQLException {
        List<Integer> numbers = new ArrayList<>(ballCount);
        for (int index = 1; index <= ballCount; index++) {
            numbers.add(rs.getObject("number_" + index, Integer.class));
        }
        return java.util.Collections.unmodifiableList(numbers);
    }

    private static RuleDescriptor ruleDescriptor(String rawPlayType, String parametersText) {
        final PlayType sourceType;
        try {
            sourceType = PlayType.valueOf(rawPlayType);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return new RuleDescriptor("UNKNOWN:" + rawPlayType + ":" + parametersText,
                    rawPlayType + " " + parametersText, rawPlayType);
        }
        List<Integer> parameters = parseParameters(parametersText);
        try {
            return normalizedRule(sourceType, rawPlayType, parametersText, parameters);
        } catch (IllegalArgumentException exception) {
            String key = rawPlayType + ":" + parametersText;
            return new RuleDescriptor(key, rawPlayType + " " + parametersText, rawPlayType);
        }
    }

    private static RuleDescriptor normalizedRule(PlayType sourceType, String rawPlayType,
                                                  String parametersText, List<Integer> parameters) {
        PlayType normalizedType = sourceType;
        List<Integer> normalizedParameters = parameters;
        String name = rawPlayType + " " + parametersText;
        switch (sourceType) {
            case FAN -> name = parameter(parameters, 0) + "番";
            case ANGLE -> {
                normalizedParameters = sorted(parameters);
                name = joinDigits(normalizedParameters) + "角";
            }
            case CAR -> {
                if (parameters.size() != 3) break;
                int excluded = java.util.stream.IntStream.rangeClosed(1, 4)
                        .filter(value -> !parameters.contains(value)).findFirst().orElse(0);
                if (excluded == 0) break;
                normalizedParameters = List.of(excluded);
                name = excluded + "车";
            }
            case STRICT -> name = parameter(parameters, 0) + "严" + parameter(parameters, 1);
            case ADD -> {
                if (parameters.size() != 3) break;
                normalizedParameters = List.of(parameters.get(0), sorted(parameters.subList(1, 3)).get(0),
                        sorted(parameters.subList(1, 3)).get(1));
                name = parameters.get(0) + "加" + joinDigits(normalizedParameters.subList(1, 3));
            }
            case POSITIVE -> name = parameter(parameters, 0) + "正";
            case TONG -> {
                if (parameters.size() != 3) break;
                normalizedParameters = List.of(parameters.get(0), sorted(parameters.subList(1, 3)).get(0),
                        sorted(parameters.subList(1, 3)).get(1));
                name = normalizedParameters.get(0) + "通" + joinDigits(normalizedParameters.subList(1, 3));
            }
            case NONE -> {
                if (parameters.size() == 2) {
                    int winner = parameters.get(0);
                    int loser = parameters.get(1);
                    if (Math.abs(winner - loser) == 2) {
                        normalizedType = PlayType.POSITIVE;
                        normalizedParameters = List.of(winner);
                        name = winner + "正";
                    } else {
                        normalizedType = PlayType.ADD;
                        normalizedParameters = List.of(winner, sorted(List.of(1, 2, 3, 4).stream()
                                .filter(value -> value != winner && value != loser).toList()).get(0),
                                sorted(List.of(1, 2, 3, 4).stream()
                                        .filter(value -> value != winner && value != loser).toList()).get(1));
                        name = winner + "加" + joinDigits(normalizedParameters.subList(1, 3));
                    }
                } else if (parameters.size() == 3) {
                    normalizedType = PlayType.TONG;
                    normalizedParameters = List.of(parameters.get(2), sorted(parameters.subList(0, 2)).get(0),
                            sorted(parameters.subList(0, 2)).get(1));
                    name = normalizedParameters.get(0) + "通" + joinDigits(normalizedParameters.subList(1, 3));
                } else {
                    break;
                }
            }
            case ODD_EVEN -> name = parameter(parameters, 0) == 1 ? "单" : "双";
            case BIG_SMALL -> name = parameter(parameters, 0) == 1 ? "大" : "小";
            case SPECIAL -> {
                normalizedParameters = sorted(parameters);
                name = normalizedParameters.stream().map(value -> String.format("%02d", value))
                        .collect(Collectors.joining("/")) + "特";
            }
            default -> throw new IllegalStateException("未处理的下注玩法");
        }
        String key = normalizedType.name() + ":" + normalizedParameters.stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        return new RuleDescriptor(key, name, normalizedType.name());
    }

    private static List<Integer> parseParameters(String text) {
        try {
            if (text == null || text.isBlank()) return List.of();
            return java.util.Arrays.stream(text.split(","))
                    .map(String::trim).map(Integer::valueOf).toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static List<Integer> sorted(List<Integer> values) {
        return values.stream().sorted().toList();
    }

    private static int parameter(List<Integer> values, int index) {
        if (index >= values.size()) throw new IllegalArgumentException("玩法参数不完整");
        return values.get(index);
    }

    private static String joinDigits(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining());
    }

    private static String value(Integer number) {
        return number == null ? "RANDOM" : number.toString();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record CurrentStats(String gameCode, String gameName, String issueNumber, String phase, int ballCount,
                               int drawResultVersion,
                               Instant bettingEndsAt, Instant drawEndsAt, Instant serverNow,
                               List<BallStats> balls) {
    }

    public record BallStats(int ballNumber, Integer presetNumber, long orderCount, BigDecimal totalStake,
                            List<PlayStats> plays) {
    }

    public record PlayStats(String ruleKey, String playType, String playName, long orderCount, BigDecimal totalStake) {
    }

    public record PresetSummary(String gameCode, String issueNumber, String phase, int ballNumber,
                                Integer presetNumber, Instant drawEndsAt, Instant serverNow) {
    }

    public record ControlSummary(long operationId, String idempotencyKey, String requestDigest, String status,
                                 String gameCode, String issueNumber, long orderCount, BigDecimal totalStake,
                                 BigDecimal ratioPercent, BigDecimal targetCenterProfit, List<Integer> numbers,
                                 BigDecimal predictedCenterProfit, Integer resultVersion) {
    }

    private record GameInfo(String displayName, int nextIssueBallCount) {
    }

    private record PlayRow(int ballNumber, String playType, String parametersText,
                           long orderCount, BigDecimal totalStake) {
    }

    private record ControlOrder(long centerOrderId, long betId, long centerAgentId, int ballNumber,
                                int centerBallNumber,
                                String localPlayType, String localParameters, BigDecimal localStake,
                                BigDecimal localOdds, String gameCode, String issueNumber,
                                String centerPlayType, String centerPlayText, BigDecimal amount,
                                BigDecimal centerOdds, BigDecimal machineOddsRate) {
    }

    private record ControlDrawSnapshot(int resultVersion, String numbersText) {
    }

    private record RuleDescriptor(String ruleKey, String playName, String playType) {
    }

    private static final class PlayAccumulator {
        private final RuleDescriptor descriptor;
        private long orderCount;
        private BigDecimal totalStake = BigDecimal.ZERO.setScale(2);

        private PlayAccumulator(RuleDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        private PlayStats toStats() {
            return new PlayStats(descriptor.ruleKey(), descriptor.playType(), descriptor.playName(),
                    orderCount, money(totalStake));
        }
    }

    private record CurrentIssue(String gameCode, String issueNumber, String phase, int ballCount, int drawRuleVersion,
                                int drawResultVersion, List<Integer> numbers, Instant bettingEndsAt,
                                Instant drawEndsAt) {
    }
}
