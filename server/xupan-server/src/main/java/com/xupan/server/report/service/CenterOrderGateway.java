package com.xupan.server.report.service;

import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.service.CenterOrderService;
import com.xupan.server.report.domain.ReportOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 本地机器 → 中心的报送网关。
 *
 * <p>负责把"这台本地机器"翻译成"中心的哪个代理身份"，再调用中心接单服务：</p>
 * <ol>
 *   <li>按 {@code agent.center_agent_id} 找到该机器的中心代理绑定；</li>
 *   <li>没绑定或绑定已停用 → {@link ReportOutcome#REJECTED}（配置不完整，不是超时）；</li>
 *   <li>用 {@code (center_agent_id, agent_order_no)} 做幂等，{@code agent_order_no} 用本地注单号，
 *       保证同一笔注单重复上报只会在中心建一单；</li>
 *   <li>中心受理 → {@code ACCEPTED}；中心拒绝 → {@code REJECTED}；
 *       中心抛业务异常（如额度不足）→ 也按 {@code REJECTED}（这是"确认未受理"，可以退单）。</li>
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
            // 未绑定中心代理身份 = 报网配置不完整；按"确认未受理"处理，代理侧可据此退单
            return ReportOutcome.REJECTED;
        }
        String agentOrderNo = "BET-" + betId;
        // 玩法参数以 params= 段随 playText 一起报给中心，中心结算时按它判定胜负
        String playText = parameters == null || parameters.isBlank()
                ? "" : "params=" + parameters.trim().replace(" ", "");
        try {
            CenterOrderService.Accepted accepted = centerOrderService.accept(centerAgentId.get(), agentOrderNo,
                    betId, machineId, gameCodeOf(machineId), issueNumber, playType, playText, odds, stake);
            return "ACCEPTED".equals(accepted.status()) ? ReportOutcome.ACCEPTED : ReportOutcome.REJECTED;
        } catch (RuntimeException exception) {
            // 中心明确拒绝（含额度不足等业务冲突）：这是"确认未受理"，不是状态未知，
            // 因此返回 REJECTED 让代理侧走失败退单，而不是 TIMEOUT（TIMEOUT 不得直接退款）。
            return ReportOutcome.REJECTED;
        }
    }

    /** 该机器绑定的、且仍启用中的中心代理身份。 */
    private Optional<Long> findActiveCenterAgentId(long machineId) {
        return jdbc.query("""
                SELECT a.center_agent_id
                  FROM agent a
                  JOIN center_agent c ON c.id = a.center_agent_id
                 WHERE a.id = ? AND a.deleted_at IS NULL AND c.status = 'ACTIVE'
                """, (rs, rowNum) -> rs.getLong("center_agent_id"), machineId).stream().findFirst();
    }

    /**
     * 上报用的游戏编码。
     *
     * <p>中心订单必须带游戏编码才能按"游戏 + 期号"批量结算。本地机器的彩种配置在
     * {@code agent_game}，取第一项作为该机器的报网游戏；没有配置时回退到 {@code K28}
     * （本地既有默认彩种），保证不会因为缺一个字段就让上报整体失败。</p>
     */
    private String gameCodeOf(long machineId) {
        return jdbc.query("""
                SELECT game_code FROM agent_game WHERE agent_id = ? ORDER BY game_code LIMIT 1
                """, (rs, rowNum) -> rs.getString("game_code"), machineId).stream().findFirst()
                .orElse("K28");
    }
}
