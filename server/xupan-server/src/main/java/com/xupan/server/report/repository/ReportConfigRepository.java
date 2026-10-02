package com.xupan.server.report.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 机器级报网配置（BY220 {@code x_robot.flyers_*}）。
 *
 * <p>边界（强制）：</p>
 * <ul>
 *   <li>本仓储**不读取** {@code agent.report_password_ciphertext} 的密文值，只暴露
 *       {@code hasPassword} 布尔，避免明文/密文从任何一层泄露到响应、日志或审计。</li>
 *   <li>不做任何外部访问；{@code report_url} 只保存不请求。</li>
 * </ul>
 *
 * <p>订单侧（上报订单实体、派生上报状态、待上报扫描）在
 * {@link ReportUpstreamOrderRepository}：V58 起本地注单与上游上报订单是两个实体，
 * 上报状态不再是 {@code game_bet} 上的一个列。</p>
 */
@Repository
public class ReportConfigRepository {

    private final JdbcTemplate jdbc;

    public ReportConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取机器的报网配置；密码只以 {@code hasPassword} 形式返回。 */
    public Optional<ReportConfigRow> findConfig(long agentId) {
        List<ReportConfigRow> rows = jdbc.query("""
                SELECT ag.id, ag.agent_code, ag.display_name,
                       ag.report_type, ag.report_auto, ag.report_online, ag.report_withdraw,
                       ag.report_username, ag.report_url, ag.report_network_code, ag.report_route_code,
                       ag.report_balance, ag.report_stake, ag.report_last_login_at,
                       CASE WHEN ag.report_password_ciphertext IS NULL
                                  OR ag.report_password_ciphertext = '' THEN FALSE ELSE TRUE END AS has_password
                  FROM agent ag
                 WHERE ag.id = ? AND ag.deleted_at IS NULL
                """, (rs, rowNum) -> new ReportConfigRow(
                rs.getLong("id"), rs.getString("agent_code"), rs.getString("display_name"),
                rs.getString("report_type"), rs.getBoolean("report_auto"), rs.getBoolean("report_online"),
                rs.getBoolean("report_withdraw"), rs.getString("report_username"), rs.getBoolean("has_password"),
                rs.getString("report_url"), rs.getString("report_network_code"), rs.getString("report_route_code"),
                amount(rs.getBigDecimal("report_balance")), amount(rs.getBigDecimal("report_stake")),
                instant(rs.getTimestamp("report_last_login_at"))), agentId);
        return rows.stream().findFirst();
    }

    /**
     * 保存报网配置。
     *
     * <p>{@code report_password_ciphertext} 只在 {@link ReportConfigUpdate#passwordCiphertext()} 非空时写入，
     * 因此"不传密码"不会清掉已保存的密文。</p>
     */
    public int updateConfig(long agentId, ReportConfigUpdate update) {
        StringBuilder sql = new StringBuilder("""
                UPDATE agent
                   SET report_type = ?, report_auto = ?, report_withdraw = ?, report_username = ?,
                       report_url = ?, report_network_code = ?, report_route_code = ?,
                       updated_at = CURRENT_TIMESTAMP
                """);
        List<Object> args = new ArrayList<>();
        args.add(update.reportType());
        args.add(update.reportAuto());
        args.add(update.reportWithdraw());
        args.add(update.reportUsername());
        args.add(update.reportUrl());
        args.add(update.reportNetworkCode());
        args.add(update.reportRouteCode());
        String ciphertext = update.passwordCiphertext();
        if (ciphertext != null && !ciphertext.isBlank()) {
            sql.append(", report_password_ciphertext = ?");
            args.add(ciphertext);
        }
        sql.append(" WHERE id = ? AND deleted_at IS NULL");
        args.add(agentId);
        return jdbc.update(sql.toString(), args.toArray());
    }

    /** 本地模拟登录：只置在线与登录时间，不发起任何外部请求。 */
    public int markOnline(long agentId, Instant loginAt) {
        return jdbc.update("""
                UPDATE agent
                   SET report_online = TRUE, report_last_login_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND deleted_at IS NULL
                """, Timestamp.from(loginAt), agentId);
    }

    /** 退出报网：只置离线，保留上次登录时间与已保存配置。 */
    public int markOffline(long agentId) {
        return jdbc.update("""
                UPDATE agent
                   SET report_online = FALSE, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND deleted_at IS NULL
                """, agentId);
    }

    /** 读取机器的报网配置（供上报任务判断是否满足上报条件）。 */
    public Optional<ReportConfigRow> findConfigByAgent(long agentId) {
        return findConfig(agentId);
    }

    /**
     * 累加机器的"押注"本地模拟值（BY220 {@code x_rbfly.betting}）。
     *
     * <p>BY220 本地环境登录时把 {@code betting} 写 0（{@code rbt/Index.php:803}），真值只由真实上游返回。
     * 本项目不接真实上游，因此这里用"已成功上报的投注额累计"作为本地模拟值：它是本项目可算的真实事实，
     * 不会被误当成上游数据。界面已标注"本地模拟"。</p>
     */
    public void addReportedStake(long agentId, BigDecimal stake) {
        if (stake == null || stake.signum() <= 0) {
            return;
        }
        jdbc.update("""
                UPDATE agent SET report_stake = report_stake + ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND deleted_at IS NULL
                """, stake, agentId);
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 机器报网配置读模型；不含密码密文。 */
    public record ReportConfigRow(long agentId, String agentCode, String agentName, String reportType,
                                  boolean reportAuto, boolean reportOnline, boolean reportWithdraw,
                                  String reportUsername, boolean hasPassword, String reportUrl,
                                  String reportNetworkCode, String reportRouteCode, BigDecimal reportBalance,
                                  BigDecimal reportStake, Instant reportLastLoginAt) {
    }

    /** 报网配置写模型；{@code passwordCiphertext} 为空表示不改动已保存的密码。 */
    public record ReportConfigUpdate(String reportType, Boolean reportAuto, Boolean reportWithdraw,
                                     String reportUsername, String passwordCiphertext, String reportUrl,
                                     String reportNetworkCode, String reportRouteCode) {
    }
}
