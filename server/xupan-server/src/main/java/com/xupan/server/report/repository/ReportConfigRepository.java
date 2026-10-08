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

    /**
     * 读取机器的报网配置（含统一飞单只读回退）；密码只以 {@code hasPassword} 形式返回。
     *
     * <p><b>BY220 依据</b>：子账号（BY220 {@code x_admin}）持有"统一飞单"凭据——
     * {@code feidanname}（飞单账号）、{@code feidanpwd}（飞单密码）、{@code feidanid}（网盘 → {@code x_rbwp}）、
     * {@code feidantype}（盘口），见 {@code admin/controller/Users.php:42-162}、
     * {@code admin/view/sub/form.html:48-77} 与 {@code admin/view/sub/list.html:57}；机器
     * （BY220 {@code x_robot} / {@code x_rbfly.flyers_*}）另有自己的飞单字段与运行状态。
     * 本项目的对应列是 {@code agent_group.unified_report_enabled / report_username /
     * report_password_ciphertext / report_network_code / report_route_code}（V35）。</p>
     *
     * <p><b>回退规则</b>：当机器自身 {@code agent.report_username} 为空、所属子账号
     * （{@code agent.group_id} 且未软删除）{@code unified_report_enabled = TRUE} 且其
     * {@code report_username} 非空时，读取结果标记 {@code inheritedFromGroup = TRUE}，并且
     * 飞单账号 / 网盘编码 / 盘口线路 / 报网类型 / {@code hasPassword}（子账号
     * {@code report_password_ciphertext}）取自子账号；自动上报 / 失败退单 /
     * 余额 / 押注 / 最近登录时间 / 上游地址 / 在线状态仍取机器自身。</p>
     *
     * <p><b>报网类型的回退（V73）</b>：BY220 机器的 {@code x_robot.flyers_type} 由所选网盘的
     * {@code code} 决定（{@code rbt/controller/Index.php:745}），而网盘表 {@code x_rbwp} 没有类型列
     * （{@code admin/controller/Index.php:42-98}）；本项目把"网盘编码"与"报网类型"拆成
     * {@code agent_group.report_network_code} 与 {@code agent_group.report_type} 两列，
     * 因此继承态下报网类型取子账号的 {@code report_type}；
     * <b>子账号未设置（{@code NULL} 或空串）时回退机器自身的 {@code agent.report_type}</b>，
     * 避免把原本能登录的机器变成"配置不完整"。机器自身列始终另外以 {@code ownReportType}
     * 透出，供写路径的"保持原值"基准使用（见 {@link #findConfig} 的调用方）。</p>
     *
     * <p><b>只读回退（强制）</b>：本方法**绝不**把子账号的账号或密码写入 {@code agent} 行：
     * {@code agent.report_username} 上有唯一约束 {@code uk_agent_report_username}（V59），
     * 复制会直接撞唯一键，同时破坏"一台机器一个上游账号"的账本原则（原则 8）。
     * 因此这里只做读取合并，写路径仍走 {@link #updateConfig} 且只写机器自身列。</p>
     */
    public Optional<ReportConfigRow> findConfig(long agentId) {
        List<ReportConfigRow> rows = jdbc.query("""
                SELECT ag.id, ag.agent_code, ag.display_name,
                       ag.report_type, ag.report_auto, ag.report_online, ag.report_withdraw,
                       ag.report_username, ag.report_url, ag.report_network_code, ag.report_route_code,
                       ag.report_balance, ag.report_stake, ag.report_last_login_at,
                       CASE WHEN ag.report_password_ciphertext IS NULL
                                  OR ag.report_password_ciphertext = '' THEN FALSE ELSE TRUE END AS has_password,
                       g.unified_report_enabled AS group_unified_report_enabled,
                       g.report_username AS group_report_username,
                       g.report_network_code AS group_report_network_code,
                       g.report_route_code AS group_report_route_code,
                       g.report_type AS group_report_type,
                       CASE WHEN g.report_password_ciphertext IS NULL
                                  OR g.report_password_ciphertext = '' THEN FALSE ELSE TRUE END AS group_has_password
                  FROM agent ag
                  LEFT JOIN agent_group g ON g.id = ag.group_id AND g.deleted_at IS NULL
                 WHERE ag.id = ? AND ag.deleted_at IS NULL
                """, (rs, rowNum) -> {
            String ownUsername = rs.getString("report_username");
            String groupUsername = rs.getString("group_report_username");
            // 只有"机器自身没有飞单账号 + 子账号开启统一飞单 + 子账号已配账号"三者同时成立才回退。
            boolean inherited = isBlank(ownUsername)
                    && rs.getBoolean("group_unified_report_enabled")
                    && !isBlank(groupUsername);
            String ownReportType = rs.getString("report_type");
            String groupReportType = rs.getString("group_report_type");
            // 子账号未设置报网类型时回退机器自身（只读回退，绝不写回 agent 行）。
            String effectiveReportType = inherited && !isBlank(groupReportType) ? groupReportType : ownReportType;
            return new ReportConfigRow(
                    rs.getLong("id"), rs.getString("agent_code"), rs.getString("display_name"),
                    effectiveReportType, ownReportType,
                    rs.getBoolean("report_auto"), rs.getBoolean("report_online"),
                    rs.getBoolean("report_withdraw"),
                    inherited ? groupUsername : ownUsername,
                    inherited ? rs.getBoolean("group_has_password") : rs.getBoolean("has_password"),
                    rs.getString("report_url"),
                    inherited ? rs.getString("group_report_network_code") : rs.getString("report_network_code"),
                    inherited ? rs.getString("group_report_route_code") : rs.getString("report_route_code"),
                    amount(rs.getBigDecimal("report_balance")), amount(rs.getBigDecimal("report_stake")),
                    instant(rs.getTimestamp("report_last_login_at")), inherited);
        }, agentId);
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

    /**
     * 读取机器的报网配置（供上报任务判断是否满足上报条件）。
     *
     * <p>与 {@link #findConfig} 是同一个读取路径：统一飞单回退必须对上报告警、人工重报、
     * 状态统计等所有读取点同时生效，因此这里只是语义别名，不另写 SQL。</p>
     */
    public Optional<ReportConfigRow> findConfigByAgent(long agentId) {
        return findConfig(agentId);
    }

    /**
     * 该上游账号是否已被**其它**未删除的机器占用（原则 8：禁止多代理共用上游账号）。
     *
     * @return 占用它的机器 id；未被占用时为 {@link Optional#empty()}
     */
    public Optional<Long> findAgentIdByReportUsername(String reportUsername, long excludeAgentId) {
        if (reportUsername == null || reportUsername.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT id FROM agent
                 WHERE report_username = ? AND id <> ? AND deleted_at IS NULL
                 ORDER BY id LIMIT 1
                """, (rs, rowNum) -> rs.getLong("id"), reportUsername.trim(), excludeAgentId)
                .stream().findFirst();
    }

    /** 释放上游账号占用（软删除机器时调用，避免占位阻挡复用）。 */
    public int releaseReportCredential(long agentId) {
        return jdbc.update("""
                UPDATE agent
                   SET report_username = NULL, report_password_ciphertext = NULL, report_online = FALSE,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, agentId);
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

    /** 空串与纯空白一律视为"没有配置"，与 BY220 的 {@code feidanname <> ''} 判断一致。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 机器报网配置读模型；不含密码密文。
     *
     * @param reportType         生效中的报网类型：机器自身有配置时是机器自身，
     *                           {@code inheritedFromGroup = TRUE} 时是子账号统一飞单的类型
     *                           （子账号未设置则回退机器自身）
     * @param ownReportType      **机器自身列** {@code agent.report_type} 的原始值（不做任何回退）。
     *                           读取展示一律用 {@code reportType}；本字段只供写路径的"保持原值"基准使用，
     *                           以免一次"只改别的字段"的保存把子账号的报网类型复制进 {@code agent} 行。
     * @param reportUsername     机器自身账号；{@code inheritedFromGroup = TRUE} 时为子账号的统一飞单账号
     * @param reportNetworkCode  机器自身网盘编码；继承时为子账号的网盘编码
     * @param reportRouteCode    机器自身盘口线路；继承时为子账号的盘口线路
     * @param hasPassword        机器自身是否已存密码；继承时为子账号是否已存密码（始终只是布尔）
     * @param inheritedFromGroup 是否来自子账号"统一飞单"回退；机器自身有配置时为 false
     */
    public record ReportConfigRow(long agentId, String agentCode, String agentName, String reportType,
                                  String ownReportType,
                                  boolean reportAuto, boolean reportOnline, boolean reportWithdraw,
                                  String reportUsername, boolean hasPassword, String reportUrl,
                                  String reportNetworkCode, String reportRouteCode, BigDecimal reportBalance,
                                  BigDecimal reportStake, Instant reportLastLoginAt, boolean inheritedFromGroup) {
    }

    /** 报网配置写模型；{@code passwordCiphertext} 为空表示不改动已保存的密码。 */
    public record ReportConfigUpdate(String reportType, Boolean reportAuto, Boolean reportWithdraw,
                                     String reportUsername, String passwordCiphertext, String reportUrl,
                                     String reportNetworkCode, String reportRouteCode) {
    }
}
