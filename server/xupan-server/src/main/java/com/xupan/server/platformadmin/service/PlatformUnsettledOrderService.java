package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.game.domain.BetCommandFormatter;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.repository.GameDataRepository;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
public class PlatformUnsettledOrderService {

    private static final String READ_PERMISSION = "UNSETTLED_ORDER_READ";
    private static final String DELETE_PERMISSION = "UNSETTLED_ORDER_DELETE";

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final GameDataRepository gameDataRepository;
    private final VirtualWalletService walletService;

    public PlatformUnsettledOrderService(JdbcTemplate jdbc, PermissionService permissionService,
                                         GameDataRepository gameDataRepository,
                                         VirtualWalletService walletService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.gameDataRepository = gameDataRepository;
        this.walletService = walletService;
    }

    @Transactional(readOnly = true)
    public Page list(int page, int pageSize, long operator) {
        requirePermission(operator, READ_PERMISSION);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw BusinessException.badRequest("UNSETTLED_ORDER_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.settlement_status = 'PENDING'
                   AND a.player_kind = 'NORMAL'
                """, Long.class);
        List<Row> items = jdbc.query("""
                SELECT b.id, b.issue_number, b.play_type, b.parameters_text, b.stake,
                       b.created_at, b.report_status,
                       COALESCE(g.username, g.display_name, g.group_code, '') AS sub_account,
                       COALESCE(m.display_name, m.agent_code, '') AS machine_name,
                       a.member_code,
                       COALESCE(u.display_name, a.display_name) AS player_name
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                  LEFT JOIN sys_user u ON u.id = a.sys_user_id
                  LEFT JOIN agent m ON m.id = a.agent_id
                  LEFT JOIN agent_group g ON g.id = m.group_id
                 WHERE b.settlement_status = 'PENDING'
                   AND a.player_kind = 'NORMAL'
                 ORDER BY b.created_at DESC, b.id DESC
                 LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new Row(
                rs.getLong("id"),
                rs.getString("sub_account"),
                rs.getString("machine_name"),
                rs.getString("member_code"),
                rs.getString("player_name"),
                rs.getString("issue_number"),
                command(rs.getString("play_type"), rs.getString("parameters_text"), rs.getBigDecimal("stake")),
                instant(rs.getTimestamp("created_at")),
                rs.getString("report_status")), pageSize, (long) (page - 1) * pageSize);
        return new Page(items, page, pageSize, total == null ? 0 : total);
    }

    @Transactional
    public CancellationResult cancel(long id, long operator) {
        requirePermission(operator, DELETE_PERMISSION);
        if (id <= 0) {
            throw BusinessException.badRequest("UNSETTLED_ORDER_ID_INVALID", "未结订单编号无效");
        }
        CancellationTarget target = jdbc.query("""
                SELECT b.id, b.user_id, b.issue_number, b.stake, b.settlement_status,
                       a.sys_user_id
                  FROM game_bet b
                  JOIN demo_user_account a ON a.id = b.user_id
                 WHERE b.id = ?
                   AND a.player_kind = 'NORMAL'
                   FOR UPDATE
                """, rs -> rs.next() ? new CancellationTarget(
                rs.getLong("id"), rs.getLong("user_id"), rs.getObject("sys_user_id", Long.class),
                rs.getString("issue_number"), rs.getBigDecimal("stake"), rs.getString("settlement_status")) : null, id);
        if (target == null) {
            throw BusinessException.notFound("UNSETTLED_ORDER_NOT_FOUND", "未结订单不存在");
        }
        if (!"PENDING".equals(target.settlementStatus())) {
            throw BusinessException.conflict("UNSETTLED_ORDER_ALREADY_SETTLED", "订单已结算或已删除，不能重复操作");
        }
        if (target.sysUserId() == null) {
            throw BusinessException.conflict("UNSETTLED_ORDER_WALLET_UNAVAILABLE", "玩家钱包身份不完整，不能删除");
        }
        Instant now = Instant.now();
        if (!gameDataRepository.cancelBetOnce(id, now)) {
            throw BusinessException.conflict("UNSETTLED_ORDER_ALREADY_SETTLED", "订单已结算或已删除，不能重复操作");
        }
        walletService.refundForCancellation(target.sysUserId(), id, target.issueNumber(), target.stake());
        audit(operator, id, target.accountId(), target.issueNumber(), target.stake());
        return new CancellationResult(id, "CANCELED", target.stake().setScale(2));
    }

    private void requirePermission(long operator, String permission) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden("UNSETTLED_ORDER_FORBIDDEN", "没有未结订单操作权限");
        }
    }

    private void audit(long operator, long betId, long accountId, String issueNumber, BigDecimal stake) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, 'DELETE', ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, DELETE_PERMISSION, "/api/admin/unsettled-orders/" + betId,
                Long.toString(betId), "accountId=" + accountId + ",issueNumber=" + issueNumber
                        + ",stake=" + stake.setScale(2));
    }

    private static String command(String playType, String parametersText, BigDecimal stake) {
        try {
            PlayType type = PlayType.valueOf(playType);
            List<Integer> parameters = Arrays.stream(parametersText == null ? new String[0] : parametersText.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Integer::valueOf)
                    .toList();
            return BetCommandFormatter.format(type, parameters, stake);
        } catch (RuntimeException exception) {
            return playType + " " + (parametersText == null ? "" : parametersText);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Page(List<Row> items, int page, int pageSize, long total) {
    }

    public record Row(long id, String subAccount, String machineName, String memberCode,
                      String playerName, String issueNumber, String command,
                      Instant createdAt, String reportStatus) {
    }

    public record CancellationResult(long id, String status, BigDecimal refundedAmount) {
    }

    private record CancellationTarget(long id, long accountId, Long sysUserId, String issueNumber,
                                      BigDecimal stake, String settlementStatus) {
    }
}
