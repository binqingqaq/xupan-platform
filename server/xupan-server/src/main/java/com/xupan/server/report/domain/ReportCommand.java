package com.xupan.server.report.domain;

import java.math.BigDecimal;

/**
 * 报网提交时使用的注单快照与机器报网配置。
 *
 * <p>只包含本地事实的最小集合：注单信息来自 {@code game_bet} 快照，
 * 报网配置来自 {@code agent.report_*}。适配器不得依赖数据库连接，
 * 以便本地模拟与测试可以完全确定地运行。</p>
 *
 * @param betId        注单主键，用于幂等与状态回写
 * @param accountId    玩家钱包账户（{@code demo_user_account.id}）
 * @param machineId    所属机器（{@code agent.id}）
 * @param issueNumber  期号
 * @param playType     玩法编码
 * @param parameters   玩法参数文本（逗号分隔，按注单快照原样）
 * @param stake        投注虚拟积分
 * @param odds         赔率快照
 * @param reportType   报网类型（BY220 {@code flyers_type}），{@code NONE} 表示不报
 * @param networkCode  上游网盘编码（BY220 {@code x_rbwp.code}）
 * @param routeCode    盘口线路 A/B/C/D
 * @param online       机器报网是否在线（BY220 {@code flyers_online}）
 */
public record ReportCommand(long betId,
                            long accountId,
                            long machineId,
                            String issueNumber,
                            String playType,
                            String parameters,
                            BigDecimal stake,
                            BigDecimal odds,
                            String reportType,
                            String networkCode,
                            String routeCode,
                            boolean online) {
}
