package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Read-only BY220-style platform fly-order report backed by persisted bets and report attempts. */
@Service
public class PlatformFlyOrderReportService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_PAGE_SIZE = 100;
    private static final String BUCKET = """
            CASE
              WHEN b.center_report_deleted_at IS NOT NULL THEN 'UNREPORTED'
              WHEN o.id IS NULL OR o.status IN ('PENDING', 'NOT_SENT') THEN 'UNREPORTED'
              WHEN o.status IN ('ACCEPTED', 'SETTLED') THEN 'REPORTED'
              WHEN o.status IN ('REJECTED', 'TIMEOUT') THEN 'FAILED'
              WHEN o.status = 'MANUAL' AND o.response_code = 'RETRY_EXHAUSTED' THEN 'FAILED'
              WHEN o.status IN ('CANCELLED', 'REFUNDED')
                   AND o.response_code IN ('CENTER_CANCELLED', 'CENTER_REFUNDED') THEN 'REPORTED'
              WHEN o.status = 'MANUAL'
                   AND o.response_code IN ('CENTER_CANCELLED', 'CENTER_REFUNDED') THEN 'REPORTED'
              ELSE NULL
            END
            """;
    private static final String FROM_WHERE = """
            FROM game_bet b
            LEFT JOIN demo_user_account a ON a.id = b.user_id
            JOIN agent ag ON ag.id = COALESCE(a.agent_id, b.agent_id_snapshot)
            LEFT JOIN agent_group g ON g.id = ag.group_id
            LEFT JOIN report_upstream_order o
                   ON o.id = (SELECT MAX(o2.id) FROM report_upstream_order o2 WHERE o2.bet_id = b.id)
            WHERE COALESCE(b.player_kind_snapshot, a.player_kind) = 'NORMAL'
              AND ag.system_owned = FALSE AND ag.deleted_at IS NULL
              AND b.created_at >= ? AND b.created_at < ?
              AND NOT (b.settlement_status = 'CANCELED' AND o.status = 'REFUNDED'
                       AND COALESCE(o.response_code, '') <> 'CENTER_REFUNDED')
            """;

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final PlatformAdminAccessService accessService;

    public PlatformFlyOrderReportService(JdbcTemplate jdbc, PermissionService permissionService,
                                         PlatformAdminAccessService accessService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.accessService = accessService;
    }

    @Transactional(readOnly = true)
    public FlyOrderPage flyOrders(String day, int cutoffHour, String requestedStatus,
                                  int page, int pageSize, Long subAccountId, long operator) {
        requirePermission(operator);
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.badRequest("REPORT_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        if (cutoffHour != 0 && cutoffHour != 6) {
            throw BusinessException.badRequest("REPORT_CUTOFF_INVALID", "日期截点必须为 0 或 6 点");
        }
        String status = normalizeStatus(requestedStatus);
        LocalDate reportDay = parseDay(day);
        Instant from = reportDay.atTime(cutoffHour, 0).atZone(BUSINESS_ZONE).toInstant();
        Instant to = reportDay.plusDays(1).atTime(cutoffHour, 0).atZone(BUSINESS_ZONE).toInstant();
        Long scopedSubAccountId = accessService.scopedGroupId(operator, subAccountId);

        List<Object> baseArgs = new ArrayList<>(List.of(Timestamp.from(from), Timestamp.from(to)));
        String scope = "";
        if (scopedSubAccountId != null) {
            scope = " AND g.id = ?";
            baseArgs.add(scopedSubAccountId);
        }
        Summary summary = summary(FROM_WHERE + scope, baseArgs);

        String filtered = FROM_WHERE + scope + " AND (" + BUCKET + ") = ?";
        List<Object> countArgs = new ArrayList<>(baseArgs);
        countArgs.add(status);
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + filtered, Long.class, countArgs.toArray());

        List<Object> pageArgs = new ArrayList<>(countArgs);
        pageArgs.add(pageSize);
        pageArgs.add((long) (page - 1) * pageSize);
        List<FlyOrderRow> items = jdbc.query("""
                SELECT b.id, b.created_at, COALESCE(a.member_code, b.player_code_snapshot) member_code,
                       COALESCE(a.display_name, b.player_name_snapshot, '匿名用户') player_name,
                       b.issue_number, b.ball_number, b.play_type, b.parameters_text,
                       b.stake, b.odds_snapshot, o.status report_status, o.response_code
                """ + filtered + " ORDER BY b.created_at DESC, b.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new FlyOrderRow(rs.getLong("id"), instant(rs.getTimestamp("created_at")),
                        rs.getString("member_code"), rs.getString("player_name"), rs.getString("issue_number"),
                        rs.getInt("ball_number"), rs.getString("play_type"), rs.getString("parameters_text"),
                        rs.getBigDecimal("stake"), rs.getBigDecimal("odds_snapshot"),
                        rs.getString("report_status"), rs.getString("response_code")), pageArgs.toArray());
        return new FlyOrderPage(reportDay, cutoffHour, status, page, pageSize, total == null ? 0 : total,
                summary, items);
    }

    private Summary summary(String fromWhere, List<Object> baseArgs) {
        List<Object> args = new ArrayList<>(baseArgs);
        String select = "SELECT\n"
                + "  COALESCE(SUM(CASE WHEN (" + BUCKET + ") = 'UNREPORTED' THEN 1 ELSE 0 END), 0) unreported_count,\n"
                + "  COALESCE(SUM(CASE WHEN (" + BUCKET + ") = 'UNREPORTED' THEN b.stake ELSE 0 END), 0) unreported_amount,\n"
                + "  COALESCE(SUM(CASE WHEN (" + BUCKET + ") = 'REPORTED' THEN 1 ELSE 0 END), 0) reported_count,\n"
                + "  COALESCE(SUM(CASE WHEN (" + BUCKET + ") = 'REPORTED' THEN b.stake ELSE 0 END), 0) reported_amount,\n"
                + "  COALESCE(SUM(CASE WHEN (" + BUCKET + ") = 'FAILED' THEN 1 ELSE 0 END), 0) failed_count,\n"
                + "  COALESCE(SUM(CASE WHEN (" + BUCKET + ") = 'FAILED' THEN b.stake ELSE 0 END), 0) failed_amount\n";
        SummaryRow row = jdbc.queryForObject(select + fromWhere, (rs, rowNum) -> new SummaryRow(rs.getLong("unreported_count"),
                rs.getBigDecimal("unreported_amount"), rs.getLong("reported_count"),
                rs.getBigDecimal("reported_amount"), rs.getLong("failed_count"),
                rs.getBigDecimal("failed_amount")), args.toArray());
        return row.toSummary();
    }

    private void requirePermission(long operator) {
        if (operator <= 0 || !permissionService.hasPermission(operator, "REPORT_READ")) {
            throw BusinessException.forbidden("REPORT_FORBIDDEN", "没有报表统计权限");
        }
    }

    private static String normalizeStatus(String status) {
        String value = status == null || status.isBlank() ? "REPORTED" : status.trim().toUpperCase();
        if (!List.of("UNREPORTED", "REPORTED", "FAILED").contains(value)) {
            throw BusinessException.badRequest("REPORT_STATUS_INVALID", "飞单状态无效");
        }
        return value;
    }

    private static LocalDate parseDay(String day) {
        if (day == null || day.isBlank()) return LocalDate.now(BUSINESS_ZONE);
        try {
            return LocalDate.parse(day);
        } catch (DateTimeParseException exception) {
            throw BusinessException.badRequest("REPORT_DATE_INVALID", "日期格式必须为 yyyy-MM-dd");
        }
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private record SummaryRow(long unreportedCount, BigDecimal unreportedAmount,
                              long reportedCount, BigDecimal reportedAmount,
                              long failedCount, BigDecimal failedAmount) {
        Summary toSummary() {
            return new Summary(unreportedCount, amount(unreportedAmount), reportedCount, amount(reportedAmount),
                    failedCount, amount(failedAmount));
        }
        private static BigDecimal amount(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    }

    public record Summary(long unreportedCount, BigDecimal unreportedAmount, long reportedCount,
                          BigDecimal reportedAmount, long failedCount, BigDecimal failedAmount) {}
    public record FlyOrderRow(long betId, Instant createdAt, String memberCode, String playerName,
                              String issueNumber, int ballNumber, String playType, String parametersText,
                              BigDecimal stake, BigDecimal odds, String reportStatus, String responseCode) {}
    public record FlyOrderPage(LocalDate day, int cutoffHour, String status, int page, int pageSize,
                               long total, Summary summary, List<FlyOrderRow> items) {}
}
