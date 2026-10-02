package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.BetCommandFormatter;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.BetTextParser;
import com.xupan.server.game.service.GameBettingConfigService;
import com.xupan.server.game.service.SettlementService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
public class PlatformOrderCorrectionService {

    private static final String READ_PERMISSION = "ORDER_CORRECTION_READ";
    private static final String MANAGE_PERMISSION = "ORDER_CORRECTION_MANAGE";
    private static final String EDIT_REASON = "超级管理后台订单改单";

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final GameDataRepository gameDataRepository;
    private final GameBettingConfigService bettingConfigService;
    private final SettlementService settlementService;
    private final VirtualWalletService walletService;

    public PlatformOrderCorrectionService(JdbcTemplate jdbc, PermissionService permissionService,
                                          GameDataRepository gameDataRepository,
                                          GameBettingConfigService bettingConfigService,
                                          SettlementService settlementService,
                                          VirtualWalletService walletService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.gameDataRepository = gameDataRepository;
        this.bettingConfigService = bettingConfigService;
        this.settlementService = settlementService;
        this.walletService = walletService;
    }

    @Transactional(readOnly = true)
    public Page list(int page, int pageSize, long operator) {
        requirePermission(operator, READ_PERMISSION);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw BusinessException.badRequest("ORDER_CORRECTION_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                  JOIN game_issue i ON i.issue_number = b.issue_number
                 WHERE b.settlement_status = 'PENDING'
                   AND a.player_kind = 'NORMAL'
                   AND i.phase = 'BETTING'
                """, Long.class);
        List<Row> items = jdbc.query("""
                SELECT b.id, b.issue_number, b.play_type, b.parameters_text, b.stake, b.created_at,
                       COALESCE(m.display_name, m.agent_code, '') AS machine_name,
                       COALESCE(u.display_name, a.display_name) AS player_name
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                  LEFT JOIN sys_user u ON u.id = a.sys_user_id
                  LEFT JOIN agent m ON m.id = a.agent_id
                  JOIN game_issue i ON i.issue_number = b.issue_number
                 WHERE b.settlement_status = 'PENDING'
                   AND a.player_kind = 'NORMAL'
                   AND i.phase = 'BETTING'
                 ORDER BY b.created_at DESC, b.id DESC
                 LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new Row(
                rs.getLong("id"),
                rs.getString("machine_name"),
                rs.getString("player_name"),
                rs.getString("issue_number"),
                instant(rs.getTimestamp("created_at")),
                money(rs.getBigDecimal("stake")),
                command(rs.getString("play_type"), rs.getString("parameters_text"), rs.getBigDecimal("stake"))),
                pageSize, (long) (page - 1) * pageSize);
        return new Page(items, page, pageSize, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public Detail detail(long id, long operator) {
        requirePermission(operator, READ_PERMISSION);
        if (id <= 0) {
            throw BusinessException.badRequest("ORDER_CORRECTION_ID_INVALID", "订单编号无效");
        }
        List<Detail> rows = jdbc.query("""
                SELECT b.id, b.issue_number, b.ball_number, b.play_type, b.parameters_text,
                       b.stake, b.odds_snapshot, b.settlement_status, b.edit_version,
                       COALESCE(m.display_name, m.agent_code, '') AS machine_name,
                       COALESCE(u.display_name, a.display_name) AS player_name
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                  LEFT JOIN sys_user u ON u.id = a.sys_user_id
                  LEFT JOIN agent m ON m.id = a.agent_id
                 WHERE b.id = ? AND a.player_kind = 'NORMAL'
                """, (rs, rowNum) -> new Detail(
                rs.getLong("id"), rs.getString("issue_number"), rs.getInt("ball_number"),
                rs.getString("machine_name"), rs.getString("player_name"),
                command(rs.getString("play_type"), rs.getString("parameters_text"), rs.getBigDecimal("stake")),
                money(rs.getBigDecimal("stake")), rs.getString("settlement_status"), rs.getInt("edit_version")), id);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("ORDER_CORRECTION_NOT_FOUND", "订单不存在");
        }
        return rows.get(0);
    }

    @Transactional
    public CorrectionResult correct(long id, String command, int ballNumber,
                                    String idempotencyKey, long operator) {
        requirePermission(operator, MANAGE_PERMISSION);
        if (id <= 0) {
            throw BusinessException.badRequest("ORDER_CORRECTION_ID_INVALID", "订单编号无效");
        }
        if (ballNumber < 1 || ballNumber > 8) {
            throw BusinessException.badRequest("ORDER_CORRECTION_BALL_INVALID", "球号必须在 1 到 8 之间");
        }
        BetTextParser.ParsedBet newBet = parseSingle(command, ballNumber);
        String key = required(idempotencyKey, "改单请求标识不能为空", 128);
        ReplayRecord replay = findReplay(key);
        if (replay != null) {
            return replay(id, newBet, replay);
        }

        Target target = lockTarget(id);
        if (!"PENDING".equals(target.settlementStatus())) {
            throw BusinessException.conflict("ORDER_CORRECTION_ALREADY_SETTLED", "订单已结算，不能改单");
        }
        Instant now = Instant.now();
        if (!"BETTING".equals(target.phase()) || target.bettingEndsAt() == null
                || !now.isBefore(target.bettingEndsAt())) {
            throw BusinessException.conflict("ORDER_CORRECTION_CLOSED", "当前期已封盘或已开奖，不能改单");
        }
        BigDecimal newStake = money(newBet.stake());
        BigDecimal newOdds = target.playType().equals(newBet.playType().name())
                ? target.odds() : gameDataRepository.findOdds(newBet.playType())
                .orElseThrow(() -> BusinessException.conflict("ORDER_CORRECTION_ODDS_MISSING", "新玩法赔率不存在"));
        try {
            settlementService.validateBet(newBet.playType(), newBet.parameters(), newStake, newOdds);
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("ORDER_CORRECTION_COMMAND_INVALID", exception.getMessage());
        }
        GameBettingConfigService.LimitRejection rejection = bettingConfigService.evaluate(
                newBet.playType(), newStake,
                bettingConfigService.loadUsageExcludingBet(target.accountId(), target.issueNumber(), id));
        if (rejection != null) {
            throw BusinessException.conflict("ORDER_CORRECTION_LIMIT_EXCEEDED",
                    bettingConfigService.describeRejection(rejection));
        }

        String newParameters = newBet.parameters().stream().map(String::valueOf)
                .reduce((left, right) -> left + "," + right).orElse("");
        int updated = jdbc.update("""
                UPDATE game_bet
                   SET play_type = ?, parameters_text = ?, ball_number = ?, stake = ?, odds_snapshot = ?,
                       edit_version = edit_version + 1, last_edited_at = ?
                 WHERE id = ? AND settlement_status = 'PENDING' AND edit_version = ?
                """, newBet.playType().name(), newParameters, ballNumber, newStake, newOdds,
                Timestamp.from(now), id, target.editVersion());
        if (updated != 1) {
            throw BusinessException.conflict("ORDER_CORRECTION_CONCURRENT_UPDATE", "订单已被其他操作修改，请刷新后重试");
        }

        BigDecimal delta = newStake.subtract(target.stake()).setScale(2, RoundingMode.UNNECESSARY);
        jdbc.update("""
                INSERT INTO game_bet_edit_record
                    (bet_id, account_id, old_ball_number, new_ball_number, operator_user_id, idempotency_key,
                     old_play_type, new_play_type, old_parameters_text, new_parameters_text,
                     old_stake, new_stake, old_odds, new_odds, stake_delta, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, target.accountId(), target.ballNumber(), ballNumber, operator, key, target.playType(), newBet.playType().name(),
                target.parametersText(), newParameters, target.stake(), newStake,
                target.odds(), newOdds, delta, EDIT_REASON);
        Long editId = jdbc.queryForObject(
                "SELECT id FROM game_bet_edit_record WHERE idempotency_key = ?", Long.class, key);

        Long ledgerId = null;
        BigDecimal balance;
        if (delta.signum() != 0) {
            var walletResult = walletService.adjustForBetEdit(operator, target.sysUserId(), id,
                    target.issueNumber(), delta.negate(), EDIT_REASON, key);
            ledgerId = walletResult.ledger().id();
            balance = walletResult.wallet().balance();
            jdbc.update("UPDATE game_bet_edit_record SET ledger_id = ? WHERE id = ?", ledgerId, editId);
        } else {
            balance = walletService.getByAccountId(target.accountId()).balance();
        }
        audit(operator, id, target, newBet, newStake, delta, ledgerId);
        return new CorrectionResult(id, newBet.playType().name(),
                command(newBet.playType().name(), newParameters, newStake),
                newStake, delta, balance, ledgerId);
    }

    private ReplayRecord findReplay(String key) {
        List<ReplayRecord> rows = jdbc.query("""
                SELECT id, bet_id, new_ball_number, new_play_type, new_parameters_text, new_stake, new_odds,
                       stake_delta, ledger_id
                  FROM game_bet_edit_record
                 WHERE idempotency_key = ?
                """, (rs, rowNum) -> new ReplayRecord(
                rs.getLong("id"), rs.getLong("bet_id"), rs.getInt("new_ball_number"), rs.getString("new_play_type"),
                rs.getString("new_parameters_text"), rs.getBigDecimal("new_stake"),
                rs.getBigDecimal("new_odds"), rs.getBigDecimal("stake_delta"),
                rs.getObject("ledger_id", Long.class)), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CorrectionResult replay(long requestedBetId, BetTextParser.ParsedBet newBet, ReplayRecord replay) {
        String parameters = joinParameters(newBet.parameters());
        if (replay.betId() != requestedBetId
                || replay.newBallNumber() != newBet.ballNumber()
                || !replay.newPlayType().equals(newBet.playType().name())
                || !replay.newParametersText().equals(parameters)
                || replay.newStake().compareTo(money(newBet.stake())) != 0) {
            throw BusinessException.conflict("ORDER_CORRECTION_IDEMPOTENCY_CONFLICT", "改单请求标识已被其他请求使用");
        }
        BigDecimal balance = replay.ledgerId() == null ? null : jdbc.queryForObject("""
                SELECT l.balance_after
                  FROM demo_balance_ledger l
                 WHERE l.id = ?
                """, BigDecimal.class, replay.ledgerId());
        return new CorrectionResult(requestedBetId, replay.newPlayType(),
                command(replay.newPlayType(), replay.newParametersText(), replay.newStake()),
                money(replay.newStake()), money(replay.stakeDelta()),
                balance == null ? null : money(balance), replay.ledgerId());
    }

    private Target lockTarget(long id) {
        return jdbc.query("""
                SELECT b.id, b.user_id, b.ball_number, b.issue_number, b.play_type, b.parameters_text, b.stake,
                       b.odds_snapshot, b.settlement_status, b.edit_version,
                       a.sys_user_id, i.phase, i.betting_ends_at
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                  JOIN game_issue i ON i.issue_number = b.issue_number
                 WHERE b.id = ? AND a.player_kind = 'NORMAL'
                   FOR UPDATE
                """, rs -> rs.next() ? new Target(
                rs.getLong("id"), rs.getLong("user_id"), rs.getInt("ball_number"), rs.getLong("sys_user_id"),
                rs.getString("issue_number"), rs.getString("play_type"),
                rs.getString("parameters_text"), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot"), rs.getString("settlement_status"),
                rs.getInt("edit_version"), rs.getString("phase"),
                instant(rs.getTimestamp("betting_ends_at"))) : null, id);
    }

    private static BetTextParser.ParsedBet parseSingle(String text, int ballNumber) {
        BetTextParser.ParseResult parsed;
        try {
            parsed = BetTextParser.parse(text, ballNumber);
        } catch (RuntimeException exception) {
            throw BusinessException.badRequest("ORDER_CORRECTION_COMMAND_INVALID", "改单指令格式不正确");
        }
        if (!parsed.accepted() || parsed.bets().size() != 1) {
            throw BusinessException.badRequest("ORDER_CORRECTION_COMMAND_INVALID", "改单指令格式不正确");
        }
        return parsed.bets().get(0);
    }

    private void requirePermission(long operator, String permission) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden("ORDER_CORRECTION_FORBIDDEN", "没有订单改单权限");
        }
    }

    private void audit(long operator, long betId, Target target, BetTextParser.ParsedBet newBet,
                       BigDecimal newStake, BigDecimal delta, Long ledgerId) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'POST', ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, MANAGE_PERMISSION, "/api/admin/order-corrections/" + betId,
                Long.toString(betId), "betId=" + betId + ",oldBall=" + target.ballNumber()
                        + ",newBall=" + newBet.ballNumber() + ",oldStake=" + target.stake()
                        + ",newStake=" + newStake + ",delta=" + delta + ",ledgerId=" + ledgerId);
    }

    private static String command(String playType, String parametersText, BigDecimal stake) {
        try {
            PlayType type = PlayType.valueOf(playType);
            List<Integer> parameters = parseParameters(parametersText);
            return BetCommandFormatter.format(type, parameters, stake);
        } catch (RuntimeException exception) {
            return playType + " " + (parametersText == null ? "" : parametersText);
        }
    }

    private static List<Integer> parseParameters(String parametersText) {
        return Arrays.stream(parametersText == null ? new String[0] : parametersText.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::valueOf)
                .toList();
    }

    private static String joinParameters(List<Integer> parameters) {
        return parameters.stream().map(String::valueOf)
                .reduce((left, right) -> left + "," + right).orElse("");
    }

    private static String required(String value, String message, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw BusinessException.badRequest("ORDER_CORRECTION_REQUEST_INVALID", message);
        }
        return value.trim();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Page(List<Row> items, int page, int pageSize, long total) {
    }

    public record Row(long id, String machineName, String playerName, String issueNumber,
                      Instant createdAt, BigDecimal stake, String command) {
    }

    public record Detail(long id, String issueNumber, int ballNumber, String machineName,
                         String playerName, String command, BigDecimal stake,
                         String settlementStatus, int editVersion) {
    }

    public record CorrectionResult(long id, String playType, String command, BigDecimal stake,
                                   BigDecimal stakeDelta, BigDecimal walletBalance, Long ledgerId) {
    }

    private record Target(long id, long accountId, int ballNumber, long sysUserId, String issueNumber,
                          String playType, String parametersText, BigDecimal stake, BigDecimal odds,
                          String settlementStatus, int editVersion, String phase,
                          Instant bettingEndsAt) {
    }

    private record ReplayRecord(long id, long betId, int newBallNumber, String newPlayType,
                                String newParametersText, BigDecimal newStake, BigDecimal newOdds,
                                BigDecimal stakeDelta, Long ledgerId) {
    }
}





