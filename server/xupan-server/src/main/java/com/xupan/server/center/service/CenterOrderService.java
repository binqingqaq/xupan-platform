package com.xupan.server.center.service;

import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.repository.CenterOrderRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 中心上游接单：代理把订单报给中心，中心校验后受理或拒绝。
 *
 * <p>对应《代理报网盘口与结算业务规则说明》§12、§14、§16.4、§23.9 与
 * {@code docs/需求基线/2026-10-02-中心上游准入基线与账本原则现状.md} §5 阶段 B。</p>
 *
 * <p><b>代理身份</b>：靠 Token 识别（§14：盘口 A/B/C/D 不是代理身份）。
 * Token 只用于查账户，失败一律返回同一个错误码，不区分"没这个代理/Token 错/代理停用"，
 * 避免把可用信息透露给探测方。</p>
 *
 * <p><b>接单校验</b>：期号格式、玩法、金额与赔率、单期敞口与可用额度。
 * 校验失败返回"已拒绝"的订单行（而不是只抛错），这样代理侧能看到拒绝原因，
 * 中心侧也留下可对账的记录。</p>
 *
 * <p><b>额度占用</b>：接单成功即从可用额度划入冻结额度（原则 1 的两层分别记账）。</p>
 *
 * <p><b>幂等</b>：{@code (代理, 代理订单号)} 唯一；重复上报直接返回首次结果，不重复扣额度（原则 6）。</p>
 */
@Service
public class CenterOrderService {

    /** 单笔上限与单期敞口上限（中心侧风控，虚拟积分）。 */
    private static final BigDecimal MAX_SINGLE_AMOUNT = new BigDecimal("100000.00");
    private static final BigDecimal MAX_ISSUE_EXPOSURE = new BigDecimal("500000.00");
    /**
     * 可受理玩法：与 {@link com.xupan.server.game.domain.PlayType} 保持一致。
     *
     * <p>刻意从枚举派生而不是手写字符串——手写极易漏项（此前就漏了 ANGLE/STRICT/POSITIVE，
     * 还多了枚举里不存在的 SPECIAL/FAN_SPECIAL），结果合法玩法被误拒、
     * 而结算侧又解析不出来。</p>
     */
    private static final java.util.Set<String> ALLOWED_PLAY_TYPES =
            java.util.Arrays.stream(com.xupan.server.game.domain.PlayType.values())
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final java.util.regex.Pattern ISSUE_PATTERN =
            java.util.regex.Pattern.compile("^[0-9]{6,12}$");

    private final CenterAgentRepository agentRepository;
    private final CenterOrderRepository orderRepository;
    private final CenterAgentService agentService;
    private final CenterTokenService tokenService;

    public CenterOrderService(CenterAgentRepository agentRepository, CenterOrderRepository orderRepository,
                              CenterAgentService agentService, CenterTokenService tokenService) {
        this.agentRepository = agentRepository;
        this.orderRepository = orderRepository;
        this.agentService = agentService;
        this.tokenService = tokenService;
    }

    /**
     * 按 Token 认证代理。
     *
     * <p>失败统一返回 401 {@code CENTER_TOKEN_INVALID}：不区分"Token 不存在"与"代理已停用"，
     * 避免把账户是否存在暴露给探测方。</p>
     */
    @Transactional(readOnly = true)
    public CenterAgentRepository.AgentRow authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw BusinessException.unauthorized("CENTER_TOKEN_INVALID", "中心 Token 无效");
        }
        CenterAgentRepository.AgentRow row = agentRepository.findByTokenHash(tokenService.hash(rawToken))
                .filter(agent -> tokenService.matches(rawToken, tokenHashOf(agent.id())))
                .orElseThrow(() -> BusinessException.unauthorized("CENTER_TOKEN_INVALID", "中心 Token 无效"));
        if (!"ACTIVE".equals(row.status())) {
            throw BusinessException.unauthorized("CENTER_TOKEN_INVALID", "中心 Token 无效");
        }
        return row;
    }

    /**
     * 接收一笔订单。
     *
     * @param agentOrderNo 代理侧订单号（幂等键）
     * @return 受理结果；{@code ACCEPTED} 表示已冻结额度，其余为拒绝原因
     */
    @Transactional
    public Accepted accept(long centerAgentId, String agentOrderNo, Long localBetId, Long localAgentId,
                           String gameCode, String issueNumber, String playType, String playText,
                           BigDecimal odds, BigDecimal amount) {
        CenterAgentRepository.AgentRow agent = agentRepository.lockById(centerAgentId)
                .orElseThrow(() -> BusinessException.unauthorized("CENTER_TOKEN_INVALID", "中心 Token 无效"));

        // 幂等：同一代理同一订单号只受理一次，重复上报直接回放首次结果
        Optional<CenterOrderRepository.OrderRow> existing =
                orderRepository.findByAgentAndOrderNo(centerAgentId, agentOrderNo);
        if (existing.isPresent()) {
            CenterOrderRepository.OrderRow row = existing.get();
            return new Accepted(row.id(), row.status(), row.rejectCode(), row.rejectReason(), true,
                    row.frozenAmount());
        }

        // 代理已停用：这是"确认未受理"，必须在中心留下一条拒绝单。
        // 只抛异常会让代理侧只看到 REJECTED 却在中心查不到任何记录，
        // 事后无法解释这笔订单为什么没被受理（对账时表现为"本地有、中心无"）。
        if (!"ACTIVE".equals(agent.status())) {
            long id = orderRepository.insert(centerAgentId, agentOrderNo, localBetId, localAgentId, gameCode,
                    issueNumber, playType, playText, odds, amount, "REJECTED", "CENTER_AGENT_DISABLED",
                    rejectReason("CENTER_AGENT_DISABLED"), BigDecimal.ZERO, null);
            return new Accepted(id, "REJECTED", "CENTER_AGENT_DISABLED",
                    rejectReason("CENTER_AGENT_DISABLED"), false, BigDecimal.ZERO);
        }

        String rejectCode = validate(agent, gameCode, issueNumber, playType, odds, amount);
        if (rejectCode != null) {
            long id = orderRepository.insert(centerAgentId, agentOrderNo, localBetId, localAgentId, gameCode,
                    issueNumber, playType, playText, odds, amount, "REJECTED", rejectCode,
                    rejectReason(rejectCode), BigDecimal.ZERO, null);
            return new Accepted(id, "REJECTED", rejectCode, rejectReason(rejectCode), false, BigDecimal.ZERO);
        }

        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP);
        // 原则 1：可用额度不足即拒绝，不做"透支"——透支会让余额与敞口脱钩
        if (agent.availableBalance().compareTo(normalizedAmount) < 0) {
            long id = orderRepository.insert(centerAgentId, agentOrderNo, localBetId, localAgentId, gameCode,
                    issueNumber, playType, playText, odds, amount, "REJECTED", "CENTER_AVAILABLE_INSUFFICIENT",
                    rejectReason("CENTER_AVAILABLE_INSUFFICIENT"), BigDecimal.ZERO, null);
            return new Accepted(id, "REJECTED", "CENTER_AVAILABLE_INSUFFICIENT",
                    rejectReason("CENTER_AVAILABLE_INSUFFICIENT"), false, BigDecimal.ZERO);
        }

        Instant now = Instant.now();
        long id = orderRepository.insert(centerAgentId, agentOrderNo, localBetId, localAgentId, gameCode,
                issueNumber, playType, playText, odds, normalizedAmount, "ACCEPTED", null, null,
                normalizedAmount, now);
        // 接单即占额度：可用 → 冻结，两层各写一条流水
        agentService.moveInternal(centerAgentId, CenterAgentService.BUCKET_AVAILABLE,
                CenterAgentService.TYPE_BET_FREEZE, "OUT", normalizedAmount,
                "接单冻结：期号 " + issueNumber, "CENTER_ORDER", Long.toString(id),
                "CENTER-FREEZE-OUT-" + id, null);
        agentService.moveInternal(centerAgentId, CenterAgentService.BUCKET_FROZEN,
                CenterAgentService.TYPE_BET_FREEZE, "IN", normalizedAmount,
                "接单冻结：期号 " + issueNumber, "CENTER_ORDER", Long.toString(id),
                "CENTER-FREEZE-IN-" + id, null);
        return new Accepted(id, "ACCEPTED", null, null, false, normalizedAmount);
    }

    /**
     * 把已接受的订单退回（对应 §12.3：超时不算失败；这里用于"确认未受理"后的撤回）。
     *
     * <p>冻结额度退回可用额度——超时本身**不**退款，只有确认未受理才退回额度。</p>
     */
    @Transactional
    public boolean release(long centerOrderId, String reason) {
        CenterOrderRepository.OrderRow order = orderRepository.findById(centerOrderId)
                .orElseThrow(() -> BusinessException.notFound("CENTER_ORDER_NOT_FOUND", "中心订单不存在"));
        if (!"ACCEPTED".equals(order.status())) {
            return false;
        }
        agentRepository.lockById(order.centerAgentId());
        agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_FROZEN,
                CenterAgentService.TYPE_BET_UNFREEZE, "OUT", order.frozenAmount(),
                reason == null ? "撤回冻结" : reason, "CENTER_ORDER", Long.toString(centerOrderId),
                "CENTER-UNFREEZE-OUT-" + centerOrderId, null);
        agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_AVAILABLE,
                CenterAgentService.TYPE_BET_UNFREEZE, "IN", order.frozenAmount(),
                reason == null ? "撤回冻结" : reason, "CENTER_ORDER", Long.toString(centerOrderId),
                "CENTER-UNFREEZE-IN-" + centerOrderId, null);
        return orderRepository.updateStatus(centerOrderId, "CANCELLED", List.of("ACCEPTED")) == 1;
    }

    @Transactional(readOnly = true)
    public List<CenterOrderRepository.OrderRow> list(Long centerAgentId, String status, int page, int pageSize) {
        return orderRepository.findByStatus(centerAgentId, status, page, pageSize);
    }

    @Transactional(readOnly = true)
    public long count(Long centerAgentId, String status) {
        return orderRepository.countByStatus(centerAgentId, status);
    }

    @Transactional(readOnly = true)
    public List<CenterOrderRepository.StatusCount> countsByStatus() {
        return orderRepository.countsByStatus();
    }

    /**
     * 接单校验：返回 {@code null} 表示通过，否则返回拒绝码。
     *
     * <p>顺序按"越便宜越先判"：格式 → 玩法 → 金额 → 赔率 → 敞口。</p>
     */
    private String validate(CenterAgentRepository.AgentRow agent, String gameCode, String issueNumber,
                            String playType, BigDecimal odds, BigDecimal amount) {
        if (gameCode == null || gameCode.isBlank()) {
            return "CENTER_GAME_REQUIRED";
        }
        if (issueNumber == null || !ISSUE_PATTERN.matcher(issueNumber.trim()).matches()) {
            return "CENTER_ISSUE_INVALID";
        }
        if (playType == null || !ALLOWED_PLAY_TYPES.contains(playType.trim().toUpperCase(java.util.Locale.ROOT))) {
            return "CENTER_PLAY_TYPE_INVALID";
        }
        if (amount == null || amount.signum() <= 0) {
            return "CENTER_AMOUNT_INVALID";
        }
        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP);
        if (normalizedAmount.compareTo(MAX_SINGLE_AMOUNT) > 0) {
            return "CENTER_AMOUNT_LIMIT_EXCEEDED";
        }
        if (odds == null || odds.signum() <= 0) {
            return "CENTER_ODDS_INVALID";
        }
        BigDecimal exposure = orderRepository.acceptedExposure(agent.id(), gameCode, issueNumber.trim());
        if (exposure.add(normalizedAmount).compareTo(MAX_ISSUE_EXPOSURE) > 0) {
            return "CENTER_ISSUE_EXPOSURE_EXCEEDED";
        }
        return null;
    }

    private static String rejectReason(String code) {
        return switch (code) {
            case "CENTER_GAME_REQUIRED" -> "缺少游戏编码";
            case "CENTER_ISSUE_INVALID" -> "期号格式无效";
            case "CENTER_PLAY_TYPE_INVALID" -> "玩法不在受理范围";
            case "CENTER_AMOUNT_INVALID" -> "金额必须大于零";
            case "CENTER_AMOUNT_LIMIT_EXCEEDED" -> "单笔金额超过中心上限";
            case "CENTER_ODDS_INVALID" -> "赔率必须大于零";
            case "CENTER_ISSUE_EXPOSURE_EXCEEDED" -> "该期敞口超过中心上限";
            case "CENTER_AVAILABLE_INSUFFICIENT" -> "中心可用额度不足";
            case "CENTER_AGENT_DISABLED" -> "中心代理已停用";
            default -> code;
        };
    }

    /** 从库里取该代理的 token 哈希（仅用于常量时间比较，不再向上返回）。 */
    private String tokenHashOf(long centerAgentId) {
        return agentRepository.findTokenHash(centerAgentId);
    }

    /** 受理结果。 */
    public record Accepted(long orderId, String status, String rejectCode, String rejectReason,
                           boolean duplicate, BigDecimal frozenAmount) {
    }
}
