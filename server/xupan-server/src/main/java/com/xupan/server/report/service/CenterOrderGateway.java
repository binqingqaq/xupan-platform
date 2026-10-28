package com.xupan.server.report.service;

import com.xupan.server.center.service.CenterOrderService;
import com.xupan.server.report.domain.ReportOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 本地机器 → 中心的报送网关。
 *
 * <p>验证本机中心账号会话并调用同进程内的中心接单服务：</p>
 * <ol>
 *   <li>按当前有效的 {@code center_machine_session} 与代理子账号归属找到中心账号；</li>
 *   <li>会话失效或账号停用时跳过派发，不伪造中心拒绝；</li>
 *   <li>用 {@code (center_agent_id, agent_order_no)} 做幂等，{@code agent_order_no} 用本地注单号，
 *       保证同一笔注单重复上报只会在中心建一单；</li>
 *   <li>中心受理 → {@code ACCEPTED}；中心业务拒绝 → {@code REJECTED}；
 *       未预期的程序或数据库异常向外传播并回滚，不能误当成明确拒绝退款。</li>
 * </ol>
 *
 * <p>超时语义（重要，对应 §12.3）：本方法在**同进程内**完成，不存在网络超时，
 * 因此这里**永不返回 {@code TIMEOUT}**。返回 TIMEOUT 的场景只有真正跨进程/跨网络时才会出现，
 * 那种情况下"状态未知、不得直接退款"的规则由调用方保证。</p>
 */
@Component
public class CenterOrderGateway {

    private final JdbcTemplate jdbc;
    private final CenterOrderService centerOrderService;

    public CenterOrderGateway(JdbcTemplate jdbc, CenterOrderService centerOrderService) {
        this.jdbc = jdbc;
        this.centerOrderService = centerOrderService;
    }

    /**
     * 提交一笔注单到中心。
     *
     * @return 中心受理结果
     */
    public ReportOutcome submit(long machineId, long betId, String issueNumber, String playType,
                                String parameters, BigDecimal stake, BigDecimal odds) {
        Optional<Long> centerAgentId = findActiveCenterAgentId(machineId);
        if (centerAgentId.isEmpty()) {
            // 会话可能过期、登出或被撤销。没有实际发送订单，不能生成拒绝回执或触发退款。
            return ReportOutcome.NOT_SENT;
        }
        return submitToCenter(centerAgentId.get(), machineId, betId, issueNumber, playType, parameters, stake, odds);
    }

    /** Replay the original stable center order key for a persisted attempt after interruption. */
    public ReportOutcome recover(long centerAgentId, long machineId, long betId, String issueNumber,
                                 String playType, String parameters, BigDecimal stake, BigDecimal odds) {
        return submitToCenter(centerAgentId, machineId, betId, issueNumber, playType, parameters, stake, odds);
    }

    private ReportOutcome submitToCenter(long centerAgentId, long machineId, long betId, String issueNumber,
                                         String playType, String parameters, BigDecimal stake, BigDecimal odds) {
        String agentOrderNo = "BET-" + betId;
        // 玩法参数以 params= 段随 playText 一起报给中心，中心结算时按它判定胜负
        String playText = parameters == null || parameters.isBlank()
                ? "" : "params=" + parameters.trim().replace(" ", "");
        CenterOrderService.Accepted accepted = centerOrderService.accept(centerAgentId, agentOrderNo,
                betId, machineId, gameCodeOf(betId), issueNumber, playType, playText, odds, stake);
        return switch (accepted.status()) {
            case "ACCEPTED", "SETTLED" -> ReportOutcome.ACCEPTED;
            case "REJECTED" -> ReportOutcome.REJECTED;
            case "PENDING" -> ReportOutcome.PENDING;
            case "TIMEOUT" -> ReportOutcome.TIMEOUT;
            case "CANCELLED" -> ReportOutcome.CENTER_CANCELLED;
            case "REFUNDED" -> ReportOutcome.CENTER_REFUNDED;
            default -> throw new IllegalStateException("未知中心订单状态: " + accepted.status());
        };
    }

    /** 该机器当前会话对应的、且仍启用中的中心代理身份。 */
    private Optional<Long> findActiveCenterAgentId(long machineId) {
        return jdbc.query("""
                SELECT c.id AS center_agent_id
                  FROM agent a
                  JOIN agent_group g ON g.id = a.group_id AND g.status = 'ACTIVE' AND g.deleted_at IS NULL
                  JOIN center_machine_session s ON s.machine_agent_id = a.id
                  JOIN center_agent c ON c.id = s.center_agent_id AND c.agent_group_id = a.group_id
                 WHERE a.id = ? AND a.status = 'ACTIVE' AND a.system_owned = FALSE
                   AND a.deleted_at IS NULL AND c.status = 'ACTIVE' AND c.expires_at > CURRENT_TIMESTAMP
                   AND s.revoked_at IS NULL AND s.expires_at > CURRENT_TIMESTAMP
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("center_agent_id"), machineId).stream().findFirst();
    }

    /** Use the persisted bet's game; a machine can have orders for more than one game. */
    private String gameCodeOf(long betId) {
        return jdbc.query("""
                SELECT game_code FROM game_bet WHERE id = ?
                """, (rs, rowNum) -> rs.getString("game_code"), betId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("报网上报注单不存在: " + betId));
    }
}
