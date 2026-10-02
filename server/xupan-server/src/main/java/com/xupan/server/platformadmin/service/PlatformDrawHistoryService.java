package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.SettlementService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class PlatformDrawHistoryService {

    private static final String READ_PERMISSION = "DRAW_HISTORY_READ";
    private static final String FORCE_PERMISSION = "DRAW_HISTORY_FORCE_SETTLE";
    private static final String FORCE_ALL_CONFIRMATION = "FORCE_SETTLE_ALL";
    private static final String SUPPLEMENT_PERMISSION = "DRAW_HISTORY_SUPPLEMENT";

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final SettlementService settlementService;
    private final VirtualWalletService walletService;

    public PlatformDrawHistoryService(JdbcTemplate jdbc, PermissionService permissionService,
                                      SettlementService settlementService,
                                      VirtualWalletService walletService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.settlementService = settlementService;
        this.walletService = walletService;
    }

    @Transactional(readOnly = true)
    public Page list(String gameCode, String issueNumber, int page, int pageSize, long operator) {
        require(operator, READ_PERMISSION, "DRAW_HISTORY_FORBIDDEN", "没有开奖历史权限");
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw BusinessException.badRequest("DRAW_HISTORY_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        String normalizedGameCode = normalizeGameCode(gameCode);
        String keyword = issueNumber == null || issueNumber.isBlank() ? null : issueNumber.trim();
        StringBuilder where = new StringBuilder(" WHERE i.number_1 IS NOT NULL AND i.game_code = ?");
        List<Object> args = new ArrayList<>();
        args.add(normalizedGameCode);
        if (keyword != null) { where.append(" AND issue_number LIKE ?"); args.add("%" + keyword + "%"); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM game_issue i" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize); pageArgs.add((long)(page - 1) * pageSize);
        List<HistoryRow> items = jdbc.query("""
                SELECT i.game_code, d.display_name AS game_name,
                       i.issue_number, i.number_1, i.number_2, i.number_3, i.number_4,
                       i.number_5, i.number_6, i.number_7, i.number_8,
                       i.phase, i.opened_at, i.settled_at,
                       (SELECT COUNT(*) FROM game_bet b
                          JOIN demo_user_account a ON a.id=b.user_id
                         WHERE b.game_code=i.game_code AND b.issue_number=i.issue_number
                           AND a.player_kind='NORMAL') bet_count,
                       (SELECT COUNT(*) FROM game_bet b
                          JOIN demo_user_account a ON a.id=b.user_id
                         WHERE b.game_code=i.game_code AND b.issue_number=i.issue_number
                           AND a.player_kind='NORMAL'
                           AND b.settlement_status='PENDING') pending_bet_count
                  FROM game_issue i
                  JOIN game_definition d ON d.game_code = i.game_code
                """ + where + " ORDER BY i.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new HistoryRow(rs.getString("game_code"), rs.getString("game_name"),
                        rs.getString("issue_number"), List.of(
                        rs.getInt("number_1"), rs.getInt("number_2"), rs.getInt("number_3"), rs.getInt("number_4"),
                        rs.getInt("number_5"), rs.getInt("number_6"), rs.getInt("number_7"), rs.getInt("number_8")),
                        rs.getString("phase"), instant(rs.getTimestamp("opened_at")), instant(rs.getTimestamp("settled_at")),
                        rs.getLong("bet_count"), rs.getLong("pending_bet_count")), pageArgs.toArray());
        return new Page(items, page, pageSize, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public List<BetRow> bets(String issueNumber, long operator) {
        require(operator, READ_PERMISSION, "DRAW_HISTORY_FORBIDDEN", "没有开奖历史权限");
        if (issueNumber == null || issueNumber.isBlank()) {
            throw BusinessException.badRequest("DRAW_HISTORY_QUERY_INVALID", "期号不能为空");
        }
        return jdbc.query("""
                SELECT b.bet_code, b.play_type, b.parameters_text, b.stake, b.odds_snapshot,
                       b.settlement_status, b.net_profit, b.explanation, b.created_at, b.settled_at,
                       a.member_code, u.display_name
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id=b.user_id
                  JOIN sys_user u ON u.id=a.sys_user_id
                 WHERE b.issue_number=? AND a.player_kind='NORMAL'
                 ORDER BY b.id
                """, (rs, n) -> new BetRow(rs.getString("bet_code"), rs.getString("display_name"),
                rs.getString("member_code"), rs.getString("play_type"), rs.getString("parameters_text"),
                rs.getBigDecimal("stake"), rs.getBigDecimal("odds_snapshot"), rs.getString("settlement_status"),
                rs.getBigDecimal("net_profit"), rs.getString("explanation"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("settled_at"))), issueNumber.trim());
    }

    @Transactional
    public SettlementSummary forceSettleIssue(String issueNumber, long operator) {
        require(operator, FORCE_PERMISSION, "DRAW_HISTORY_FORCE_FORBIDDEN", "没有强制结算权限");
        if (issueNumber == null || issueNumber.isBlank()) {
            throw BusinessException.badRequest("DRAW_HISTORY_QUERY_INVALID", "期号不能为空");
        }
        String normalizedIssue = issueNumber.trim();
        HistoryTarget history = lockHistory(normalizedIssue);
        int settled = settleLockedHistory(history);
        audit(operator, normalizedIssue, 1, settled);
        return new SettlementSummary(false, 1, settled, "已强制结算 " + settled + " 条订单");
    }

    @Transactional
    public SettlementSummary forceSettleAll(String confirmation, boolean preview, long operator) {
        require(operator, FORCE_PERMISSION, "DRAW_HISTORY_FORCE_FORBIDDEN", "没有强制结算权限");
        if (!FORCE_ALL_CONFIRMATION.equals(confirmation)) {
            throw BusinessException.badRequest("DRAW_HISTORY_CONFIRM_REQUIRED", "缺少强制结算确认参数");
        }
        List<IssueKey> issues = jdbc.query("""
                SELECT DISTINCT b.game_code, b.issue_number
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id=b.user_id
                 WHERE a.player_kind='NORMAL' AND b.settlement_status='PENDING'
                 ORDER BY b.game_code, b.issue_number
                """, (rs, rowNum) -> new IssueKey(rs.getString("game_code"), rs.getString("issue_number")));
        Long pendingOrders = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id=b.user_id
                 WHERE a.player_kind='NORMAL' AND b.settlement_status='PENDING'
                """, Long.class);
        if (preview) {
            return new SettlementSummary(true, issues.size(), pendingOrders == null ? 0L : pendingOrders, "预检完成");
        }
        int settled = 0;
        for (IssueKey issue : issues) {
            settled += settleIssue(issue.gameCode(), issue.issueNumber());
        }
        audit(operator, "ALL", issues.size(), settled);
        return new SettlementSummary(false, issues.size(), settled,
                "已强制结算 " + issues.size() + " 期，共 " + settled + " 条订单");
    }


    @Transactional
    public SupplementSummary supplement(String gameCode, String issueNumber, List<Integer> numbers,
                                        Instant openedAt, long operator) {
        require(operator, SUPPLEMENT_PERMISSION, "DRAW_HISTORY_SUPPLEMENT_FORBIDDEN", "没有补期权限");
        String normalizedGameCode = normalizeGameCode(gameCode);
        String normalizedIssue = requiredIssue(issueNumber);
        List<Integer> normalizedNumbers = validateNumbers(numbers);
        Instant normalizedOpenedAt = openedAt == null ? Instant.now() : openedAt;
        HistoryTarget existing = lockExistingHistory(normalizedGameCode, normalizedIssue);
        boolean created = existing == null;
        if (existing != null && existing.numbers().stream().anyMatch(value -> value > 0)) {
            throw BusinessException.conflict("DRAW_HISTORY_ALREADY_DRAWN", "此期数已经有开奖号码");
        }
        if (created) {
            jdbc.update("""
                    INSERT INTO game_issue
                        (game_code, issue_number, status, phase, number_1, number_2, number_3, number_4,
                         number_5, number_6, number_7, number_8, issue_started_at, opened_at,
                         closed_at, settled_at)
                    VALUES (?, ?, 'CLOSED', 'SETTLED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, normalizedGameCode, normalizedIssue, normalizedNumbers.get(0), normalizedNumbers.get(1),
                    normalizedNumbers.get(2), normalizedNumbers.get(3), normalizedNumbers.get(4),
                    normalizedNumbers.get(5), normalizedNumbers.get(6), normalizedNumbers.get(7),
                    Timestamp.from(normalizedOpenedAt), Timestamp.from(normalizedOpenedAt),
                    Timestamp.from(normalizedOpenedAt), Timestamp.from(normalizedOpenedAt));
        } else {
            int updated = jdbc.update("""
                    UPDATE game_issue
                       SET status='CLOSED', phase='SETTLED',
                           number_1=?, number_2=?, number_3=?, number_4=?, number_5=?, number_6=?, number_7=?, number_8=?,
                           opened_at=COALESCE(opened_at, ?), closed_at=COALESCE(closed_at, ?),
                           settled_at=COALESCE(settled_at, ?), updated_at=CURRENT_TIMESTAMP
                     WHERE game_code=? AND issue_number=? AND number_1 IS NULL
                    """, normalizedNumbers.get(0), normalizedNumbers.get(1), normalizedNumbers.get(2),
                    normalizedNumbers.get(3), normalizedNumbers.get(4), normalizedNumbers.get(5),
                    normalizedNumbers.get(6), normalizedNumbers.get(7), Timestamp.from(normalizedOpenedAt),
                    Timestamp.from(normalizedOpenedAt), Timestamp.from(normalizedOpenedAt),
                    normalizedGameCode, normalizedIssue);
            if (updated != 1) {
                throw BusinessException.conflict("DRAW_HISTORY_ALREADY_DRAWN", "此期数已经有开奖号码");
            }
        }
        int settled = settleIssue(normalizedGameCode, normalizedIssue);
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'POST', '/api/admin/draw-history/supplement', ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, SUPPLEMENT_PERMISSION, normalizedIssue,
                "gameCode=" + normalizedGameCode + ",created=" + created + ",orders=" + settled);
        return new SupplementSummary(created, settled, settled == 0 ? "补期成功，无待结算订单" : "补期成功，已结算 " + settled + " 条订单");
    }

    private int settleIssue(String gameCode, String issueNumber) {
        return settleLockedHistory(lockHistory(gameCode, issueNumber));
    }

    private int settleLockedHistory(HistoryTarget history) {
        List<PendingBet> bets = lockPendingBets(history.gameCode(), history.issueNumber());
        Instant settledAt = Instant.now();
        int settled = 0;
        for (PendingBet bet : bets) {
            BallResult ballResult = BallResult.fromNumber(history.numbers().get(bet.ballNumber() - 1));
            var settlement = settlementService.settle(bet.playType(), bet.parameters(),
                    bet.stake(), bet.odds(), ballResult);
            int updated = jdbc.update("""
                    UPDATE game_bet
                       SET settlement_status=?, net_profit=?, explanation=?, settled_at=?
                     WHERE id=? AND settlement_status='PENDING'
                    """, settlement.status().name(), settlement.netProfit(), settlement.explanation(),
                    Timestamp.from(settledAt), bet.id());
            if (updated != 1) {
                throw BusinessException.conflict("DRAW_HISTORY_FORCE_CONFLICT", "注单已被其他操作结算，请刷新后重试");
            }
            BigDecimal payout = settlement.stake().add(settlement.netProfit());
            if (payout.signum() > 0) {
                walletService.creditForSettlement(bet.sysUserId(), bet.id(), history.issueNumber(), payout,
                        "强制结算：" + history.issueNumber());
            }
            settled++;
        }
        return settled;
    }


    private HistoryTarget lockExistingHistory(String gameCode, String issueNumber) {
        List<HistoryTarget> rows = jdbc.query("""
                SELECT game_code, issue_number, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8
                  FROM game_issue
                 WHERE game_code=? AND issue_number=?
                   FOR UPDATE
                """, (rs, n) -> new HistoryTarget(rs.getString("game_code"), rs.getString("issue_number"), List.of(
                        rs.getInt("number_1"), rs.getInt("number_2"), rs.getInt("number_3"), rs.getInt("number_4"),
                        rs.getInt("number_5"), rs.getInt("number_6"), rs.getInt("number_7"), rs.getInt("number_8"))),
                gameCode, issueNumber);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String normalizeGameCode(String gameCode) {
        String normalized = gameCode == null || gameCode.isBlank()
                ? GameDataRepository.DEFAULT_GAME_CODE
                : gameCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,32}")) {
            throw BusinessException.badRequest("DRAW_HISTORY_GAME_CODE_INVALID", "彩种键名不合法");
        }
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM game_definition
                 WHERE game_code = ? AND deleted_at IS NULL
                """, Long.class, normalized);
        if (count == null || count == 0) {
            throw BusinessException.badRequest("DRAW_HISTORY_GAME_NOT_FOUND", "彩种不存在");
        }
        return normalized;
    }

    private static String requiredIssue(String issueNumber) {
        if (issueNumber == null || issueNumber.isBlank() || issueNumber.trim().length() > 64) {
            throw BusinessException.badRequest("DRAW_HISTORY_QUERY_INVALID", "期号不能为空且不能超过 64 个字符");
        }
        return issueNumber.trim();
    }

    private static List<Integer> validateNumbers(List<Integer> numbers) {
        if (numbers == null || numbers.size() != 8
                || numbers.stream().anyMatch(value -> value == null || value < 1 || value > 20)) {
            throw BusinessException.badRequest("DRAW_HISTORY_NUMBERS_INVALID", "开奖号码必须是 8 个 1 到 20 的数字");
        }
        return List.copyOf(numbers);
    }

    private HistoryTarget lockHistory(String issueNumber) {
        List<HistoryTarget> rows = jdbc.query("""
                SELECT game_code, issue_number, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8
                  FROM game_issue
                 WHERE issue_number=?
                   FOR UPDATE
                """, (rs, n) -> new HistoryTarget(rs.getString("game_code"), rs.getString("issue_number"), List.of(
                        rs.getInt("number_1"), rs.getInt("number_2"), rs.getInt("number_3"), rs.getInt("number_4"),
                        rs.getInt("number_5"), rs.getInt("number_6"), rs.getInt("number_7"), rs.getInt("number_8"))),
                issueNumber);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("DRAW_HISTORY_NOT_FOUND", "未找到对应开奖记录");
        }
        if (rows.get(0).numbers().stream().anyMatch(value -> value == 0)) {
            throw BusinessException.conflict("DRAW_HISTORY_NOT_DRAWN", "该期还没有开奖号码，不能强制结算");
        }
        return rows.get(0);
    }

    private HistoryTarget lockHistory(String gameCode, String issueNumber) {
        List<HistoryTarget> rows = jdbc.query("""
                SELECT game_code, issue_number, number_1, number_2, number_3, number_4,
                       number_5, number_6, number_7, number_8
                  FROM game_issue
                 WHERE game_code=? AND issue_number=?
                   FOR UPDATE
                """, (rs, n) -> new HistoryTarget(rs.getString("game_code"), rs.getString("issue_number"), List.of(
                        rs.getInt("number_1"), rs.getInt("number_2"), rs.getInt("number_3"), rs.getInt("number_4"),
                        rs.getInt("number_5"), rs.getInt("number_6"), rs.getInt("number_7"), rs.getInt("number_8"))),
                gameCode, issueNumber);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("DRAW_HISTORY_NOT_FOUND", "未找到对应开奖记录");
        }
        if (rows.get(0).numbers().stream().anyMatch(value -> value == 0)) {
            throw BusinessException.conflict("DRAW_HISTORY_NOT_DRAWN", "该期还没有开奖号码，不能强制结算");
        }
        return rows.get(0);
    }

    private List<PendingBet> lockPendingBets(String gameCode, String issueNumber) {
        return jdbc.query("""
                SELECT b.id, b.user_id, a.sys_user_id, b.ball_number, b.play_type,
                       b.parameters_text, b.stake, b.odds_snapshot
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id=b.user_id
                 WHERE b.game_code=? AND b.issue_number=? AND a.player_kind='NORMAL'
                   AND b.settlement_status='PENDING'
                 ORDER BY b.id
                   FOR UPDATE
                """, (rs, n) -> new PendingBet(rs.getLong("id"), rs.getLong("user_id"),
                rs.getLong("sys_user_id"), rs.getInt("ball_number"), PlayType.valueOf(rs.getString("play_type")),
                parseParameters(rs.getString("parameters_text")), rs.getBigDecimal("stake"),
                rs.getBigDecimal("odds_snapshot")), gameCode, issueNumber);
    }

    private void audit(long operator, String resourceId, int histories, int orders) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'POST', '/api/admin/draw-history/force-settle', ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, FORCE_PERMISSION, resourceId, "histories=" + histories + ",orders=" + orders);
    }

    private void require(long operator, String permission, String code, String message) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden(code, message);
        }
    }

    private static List<Integer> parseParameters(String parametersText) {
        return java.util.Arrays.stream(parametersText == null ? new String[0] : parametersText.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::valueOf)
                .toList();
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record Page(List<HistoryRow> items, int page, int pageSize, long total) {}
    public record HistoryRow(String gameCode, String gameName, String issueNumber, List<Integer> balls,
                             String phase, Instant openedAt, Instant settledAt,
                             long betCount, long pendingBetCount) {}
    public record BetRow(String betCode, String playerName, String memberCode, String playType,
                         String parametersText, BigDecimal stake, BigDecimal odds, String settlementStatus,
                         BigDecimal netProfit, String explanation, Instant createdAt, Instant settledAt) {}
    public record SettlementSummary(boolean preview, long histories, long orders, String message) {}
    public record SupplementSummary(boolean created, int orders, String message) {}
    private record IssueKey(String gameCode, String issueNumber) {}
    private record HistoryTarget(String gameCode, String issueNumber, List<Integer> numbers) {}
    private record PendingBet(long id, long accountId, long sysUserId, int ballNumber, PlayType playType,
                              List<Integer> parameters, BigDecimal stake, BigDecimal odds) {}
}
