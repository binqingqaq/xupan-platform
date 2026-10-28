package com.xupan.server.center.service;

import com.xupan.server.agent.service.AgentLedgerService;
import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.repository.CenterOrderRepository;
import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.service.SettlementResult;
import com.xupan.server.game.service.SettlementService;
import com.xupan.server.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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

    private static final Logger log = LoggerFactory.getLogger(CenterOrderService.class);

    /** 单笔上限与单期敞口上限的**全局默认值**（代理级未配置时使用；虚拟积分）。 */
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
    private final AgentLedgerService agentLedgerService;
    private final SettlementService settlementService;
    /** 异常行为识别的滚动窗口与命中阈值（P1-C1）。窗口为 0 或阈值 <= 0 时关闭该规则。 */
    private final Duration behaviorWindow;
    private final int behaviorHitThreshold;

    public CenterOrderService(CenterAgentRepository agentRepository, CenterOrderRepository orderRepository,
                              CenterAgentService agentService, CenterTokenService tokenService,
                              AgentLedgerService agentLedgerService, SettlementService settlementService,
                              @Value("${xupan.center.risk.behavior-window:10m}") Duration behaviorWindow,
                              @Value("${xupan.center.risk.behavior-hit-threshold:30}") int behaviorHitThreshold) {
        this.agentRepository = agentRepository;
        this.orderRepository = orderRepository;
        this.agentService = agentService;
        this.tokenService = tokenService;
        this.agentLedgerService = agentLedgerService;
        this.settlementService = settlementService;
        this.behaviorWindow = behaviorWindow == null || behaviorWindow.isNegative() ? Duration.ZERO : behaviorWindow;
        this.behaviorHitThreshold = behaviorHitThreshold;
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

        if (localBetId == null || localAgentId == null || localBetId <= 0 || localAgentId <= 0) {
            throw BusinessException.badRequest("CENTER_LOCAL_BET_REQUIRED", "中心接单必须关联本地注单和报单机器");
        }
        CenterOrderRepository.LocalBetSnapshot bet = orderRepository
                .findLocalBetSnapshot(centerAgentId, localBetId, localAgentId)
                .orElseThrow(() -> BusinessException.badRequest("CENTER_LOCAL_BET_INVALID",
                        "本地注单不存在，或注单机器不属于该中心账号的子账号"));
        if (!"PENDING".equals(bet.settlementStatus())) {
            throw BusinessException.conflict("CENTER_LOCAL_BET_NOT_PENDING", "只有未结算的本地注单可以上报");
        }

        // 所有可变请求字段都以已落库注单为准，避免浏览器/调用方提交赔率、金额或玩法覆盖投注事实。
        gameCode = bet.gameCode();
        issueNumber = bet.issueNumber();
        playType = bet.playType();
        playText = bet.parametersText() == null || bet.parametersText().isBlank()
                ? "" : "params=" + bet.parametersText().trim();
        odds = bet.odds();
        amount = bet.stake();

        // 代理已停用：这是"确认未受理"，必须在中心留下一条拒绝单。
        // 只抛异常会让代理侧只看到 REJECTED 却在中心查不到任何记录，
        // 事后无法解释这笔订单为什么没被受理（对账时表现为"本地有、中心无"）。
        if (!"ACTIVE".equals(agent.status())) {
            long id = insertRejected(centerAgentId, agentOrderNo, bet, playText, "CENTER_AGENT_DISABLED",
                    BigDecimal.ZERO);
            return new Accepted(id, "REJECTED", "CENTER_AGENT_DISABLED",
                    rejectReason("CENTER_AGENT_DISABLED"), false, BigDecimal.ZERO);
        }

        String rejectCode = validate(agent, gameCode, issueNumber, playType, odds, amount);
        if (rejectCode != null) {
            long id = insertRejected(centerAgentId, agentOrderNo, bet, playText, rejectCode, BigDecimal.ZERO);
            recordRiskHit(agent, rejectCode, gameCode, issueNumber, agentOrderNo, amount);
            return new Accepted(id, "REJECTED", rejectCode, rejectReason(rejectCode), false, BigDecimal.ZERO);
        }

        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal riskAmount;
        try {
            riskAmount = maximumNetPayout(playType, playText, normalizedAmount, odds,
                    bet.machineOddsRateSnapshot());
        } catch (IllegalArgumentException invalidSnapshot) {
            String code = "CENTER_RISK_SNAPSHOT_INVALID";
            long id = insertRejected(centerAgentId, agentOrderNo, bet, playText, code, BigDecimal.ZERO);
            recordRiskHit(agent, code, gameCode, issueNumber, agentOrderNo, normalizedAmount);
            return new Accepted(id, "REJECTED", code, rejectReason(code), false, BigDecimal.ZERO);
        }

        BigDecimal exposureLimit = agent.maxIssueExposure() != null
                ? agent.maxIssueExposure() : MAX_ISSUE_EXPOSURE;
        BigDecimal exposure = orderRepository.acceptedExposure(agent.id(), gameCode, issueNumber.trim());
        if (exposure.add(riskAmount).compareTo(exposureLimit) > 0) {
            String code = "CENTER_ISSUE_EXPOSURE_EXCEEDED";
            long id = insertRejected(centerAgentId, agentOrderNo, bet, playText, code, riskAmount);
            recordRiskHit(agent, code, gameCode, issueNumber, agentOrderNo, riskAmount);
            return new Accepted(id, "REJECTED", code, rejectReason(code), false, BigDecimal.ZERO);
        }

        // 异常行为识别（P1-C1）：短时高频试探。放在最后一道，避免对正常单产生额外查询开销。
        String behaviorCode = detectAbnormalBehavior(agent, agentOrderNo);
        if (behaviorCode != null) {
            long id = insertRejected(centerAgentId, agentOrderNo, bet, playText, behaviorCode, riskAmount);
            recordRiskHit(agent, behaviorCode, gameCode, issueNumber, agentOrderNo, riskAmount);
            return new Accepted(id, "REJECTED", behaviorCode, rejectReason(behaviorCode), false, BigDecimal.ZERO);
        }

        // 锁顺序固定为 center_agent -> agent。中心额度先承担风险，缺口只由当前报单机器补足。
        BigDecimal machineBalance = agentLedgerService.lockMachineScore(bet.machineId());
        BigDecimal centerFunded = agent.availableBalance().min(riskAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal machineFunded = riskAmount.subtract(centerFunded).setScale(2, RoundingMode.HALF_UP);
        if (machineBalance.compareTo(machineFunded) < 0) {
            String code = "CENTER_RISK_CAPACITY_INSUFFICIENT";
            long id = insertRejected(centerAgentId, agentOrderNo, bet, playText, code, riskAmount);
            recordRiskHit(agent, code, gameCode, issueNumber, agentOrderNo, riskAmount);
            return new Accepted(id, "REJECTED", code, rejectReason(code), false, BigDecimal.ZERO);
        }

        Instant now = Instant.now();
        long id = orderRepository.insert(centerAgentId, agentOrderNo, bet.betId(), bet.machineId(),
                bet.gameCode(), bet.issueNumber(), bet.ballNumber(), playType, playText,
                odds, normalizedAmount, riskAmount, 1, centerFunded, machineFunded,
                bet.machineOddsRateSnapshot(), "ACCEPTED", null, null, now);
        if (centerFunded.signum() > 0) {
            agentService.moveInternal(centerAgentId, CenterAgentService.BUCKET_AVAILABLE,
                    CenterAgentService.TYPE_BET_FREEZE, "OUT", centerFunded,
                    "中心风险冻结：期号 " + issueNumber, "CENTER_ORDER", Long.toString(id),
                    "CENTER-FREEZE-OUT-" + id, null);
            agentService.moveInternal(centerAgentId, CenterAgentService.BUCKET_FROZEN,
                    CenterAgentService.TYPE_BET_FREEZE, "IN", centerFunded,
                    "中心风险冻结：期号 " + issueNumber, "CENTER_ORDER", Long.toString(id),
                    "CENTER-FREEZE-IN-" + id, null);
        }
        agentLedgerService.reserveCenterRisk(bet.machineId(), machineFunded, id);
        return new Accepted(id, "ACCEPTED", null, null, false, centerFunded);
    }

    private long insertRejected(long centerAgentId, String agentOrderNo,
                                CenterOrderRepository.LocalBetSnapshot bet, String playText,
                                String rejectCode, BigDecimal riskAmount) {
        return orderRepository.insert(centerAgentId, agentOrderNo, bet.betId(), bet.machineId(), bet.gameCode(),
                bet.issueNumber(), bet.ballNumber(), bet.playType(), playText, bet.odds(), bet.stake(),
                riskAmount, 1, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                bet.machineOddsRateSnapshot(), "REJECTED", rejectCode, rejectReason(rejectCode), null);
    }

    private BigDecimal maximumNetPayout(String playTypeName, String playText,
                                        BigDecimal stake, BigDecimal odds,
                                        BigDecimal machineOddsRateSnapshot) {
        PlayType playType = PlayType.valueOf(playTypeName.trim().toUpperCase(java.util.Locale.ROOT));
        List<Integer> parameters = CenterSettlementService.parseParameters(playText);
        BigDecimal maximum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal normalizedOdds = CenterSettlementService.normalizeOdds(odds);
        for (int number = 1; number <= 20; number++) {
            SettlementResult outcome = settlementService.settle(playType, parameters, stake, normalizedOdds,
                    null, machineOddsRateSnapshot, BallResult.fromNumber(number));
            if (outcome.netProfit().compareTo(maximum) > 0) {
                maximum = outcome.netProfit();
            }
        }
        return maximum.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal riskForConfirmation(CenterOrderRepository.OrderRow order) {
        if (order.riskPolicyVersion() == 1) {
            return nz(order.riskAmount());
        }
        if (order.localBetId() == null || order.ballNumber() == null
                || order.ballNumber() < 1 || order.ballNumber() > 8
                || order.playType() == null || order.playText() == null
                || order.amount() == null || order.odds() == null
                || ("CAR".equalsIgnoreCase(order.playType()) && order.machineOddsRateSnapshot() == null)) {
            throw BusinessException.conflict("CENTER_RISK_SNAPSHOT_REQUIRED",
                    "该历史订单的风险快照不完整，需先完成人工核对");
        }
        try {
            return maximumNetPayout(order.playType(), order.playText(), order.amount(), order.odds(),
                    order.machineOddsRateSnapshot());
        } catch (IllegalArgumentException invalidSnapshot) {
            throw BusinessException.conflict("CENTER_RISK_SNAPSHOT_REQUIRED",
                    "该历史订单无法按当前玩法规则还原最大风险，需先完成人工核对");
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }

    /**
     * 处置一笔中心订单（C-2 / C-3）：把"状态未知"或需要人工介入的订单明确落到终态。
     *
     * <p>这是需求 §12.3「超时不算失败」在中心侧的落地。阶段 B 只有自动流转，
     * 超时单会永远挂着；本方法提供四个明确动作：</p>
     *
     * <table>
     *   <tr><td>{@code CONFIRM_ACCEPTED}</td><td>TIMEOUT/PENDING → ACCEPTED</td>
     *       <td>查证后确认其实已受理，冻结额度保持占用</td></tr>
     *   <tr><td>{@code CONFIRM_FAILED}</td><td>TIMEOUT/PENDING → REJECTED</td>
     *       <td>查证后确认未受理，**不退款**（没有占用过额度）</td></tr>
     *   <tr><td>{@code REFUND}</td><td>ACCEPTED → REFUNDED</td>
     *       <td>已受理但要退回：冻结额度退回可用额度</td></tr>
     *   <tr><td>{@code CANCEL}</td><td>ACCEPTED → CANCELLED</td>
     *       <td>流局/取消：冻结额度退回可用额度</td></tr>
     * </table>
     *
     * <p><b>幂等</b>：处置流水以 {@code idempotencyKey} 唯一。重复提交同一键直接回放首次结果，
     * 不会二次改状态、更不会二次退额度。</p>
     *
     * <p><b>额度回流一律走账本</b>：冻结 → 可用的两层各写一条流水，保证
     * {@code countInconsistentBuckets} 始终为 0。</p>
     */
    @Transactional
    public DispositionResult dispose(long centerOrderId, String action, String reason,
                                     String idempotencyKey, Long operatorUserId) {
        if (action == null || action.isBlank()) {
            throw BusinessException.badRequest("CENTER_DISPOSITION_ACTION_REQUIRED", "处置动作不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw BusinessException.badRequest("CENTER_DISPOSITION_KEY_REQUIRED", "处置幂等键不能为空");
        }
        String normalizedAction = action.trim().toUpperCase(java.util.Locale.ROOT);

        // 幂等：同一幂等键只生效一次
        Optional<CenterOrderRepository.DispositionRow> existing =
                orderRepository.findDispositionByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayDisposition(centerOrderId, existing.get());
        }

        CenterOrderRepository.OrderRow order = orderRepository.findById(centerOrderId)
                .orElseThrow(() -> BusinessException.notFound("CENTER_ORDER_NOT_FOUND", "中心订单不存在"));
        String from = order.status();

        return switch (normalizedAction) {
            case "CONFIRM_ACCEPTED" -> confirmAccepted(order, from, reason, idempotencyKey, operatorUserId);
            case "CONFIRM_FAILED" -> confirmFailed(order, from, reason, idempotencyKey, operatorUserId);
            case "REFUND" -> releaseWithStatus(order, from, "REFUNDED", "REFUND", reason,
                    idempotencyKey, operatorUserId);
            case "CANCEL" -> releaseWithStatus(order, from, "CANCELLED", "CANCEL", reason,
                    idempotencyKey, operatorUserId);
            default -> throw BusinessException.badRequest("CENTER_DISPOSITION_ACTION_INVALID",
                    "处置动作必须是 CONFIRM_ACCEPTED / CONFIRM_FAILED / REFUND / CANCEL");
        };
    }

    /** 查证后确认已受理：TIMEOUT/PENDING → ACCEPTED，额度保持冻结占用。 */
    private DispositionResult confirmAccepted(CenterOrderRepository.OrderRow order, String from, String reason,
                                              String idempotencyKey, Long operatorUserId) {
        if (!List.of("TIMEOUT", "PENDING").contains(from)) {
            throw BusinessException.conflict("CENTER_DISPOSITION_STATUS_INVALID",
                    "只有待处理或超时的订单才能确认受理，当前状态：" + from);
        }
        CenterAgentRepository.AgentRow locked = agentRepository.lockById(order.centerAgentId())
                .orElseThrow(() -> BusinessException.notFound("CENTER_AGENT_NOT_FOUND", "中心账号不存在"));
        if (order.localAgentId() == null) {
            throw BusinessException.conflict("CENTER_MACHINE_RESERVATION_INVALID", "待确认订单缺少报单机器");
        }
        BigDecimal riskAmount = riskForConfirmation(order);
        BigDecimal machineBalance = agentLedgerService.lockMachineScore(order.localAgentId());
        BigDecimal centerFunded = locked.availableBalance().min(riskAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal machineFunded = riskAmount.subtract(centerFunded).setScale(2, RoundingMode.HALF_UP);
        if (machineBalance.compareTo(machineFunded) < 0) {
            throw BusinessException.conflict("CENTER_RISK_CAPACITY_INSUFFICIENT",
                    "中心可用额度和当前机器积分合计不足以覆盖最大净赔付风险");
        }
        int updated = orderRepository.acceptWithRiskReservation(order.id(), riskAmount,
                centerFunded, machineFunded);
        if (updated == 0) {
            DispositionResult duplicate = replayConcurrentDisposition(order.id(), idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            throw BusinessException.conflict("CENTER_DISPOSITION_CONFLICT", "订单状态已变化，请刷新后重试");
        }
        orderRepository.insertDisposition(order.id(), "CONFIRM_ACCEPTED", from, "ACCEPTED",
                BigDecimal.ZERO.setScale(2), reason, idempotencyKey, operatorUserId);
        if (centerFunded.signum() > 0) {
            agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_AVAILABLE,
                    CenterAgentService.TYPE_BET_FREEZE, "OUT", centerFunded,
                    "人工确认受理：订单 " + order.id(), "CENTER_ORDER", Long.toString(order.id()),
                    "CENTER-CONFIRM-FREEZE-OUT-" + order.id(), operatorUserId);
            agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_FROZEN,
                    CenterAgentService.TYPE_BET_FREEZE, "IN", centerFunded,
                    "人工确认受理：订单 " + order.id(), "CENTER_ORDER", Long.toString(order.id()),
                    "CENTER-CONFIRM-FREEZE-IN-" + order.id(), operatorUserId);
        }
        agentLedgerService.reserveCenterRisk(order.localAgentId(), machineFunded, order.id());
        return new DispositionResult(order.id(), "ACCEPTED", "CONFIRM_ACCEPTED", riskAmount, null, false);
    }

    /** 查证后确认未受理：TIMEOUT/PENDING → REJECTED。未占用过额度，因此**不退款**。 */
    private DispositionResult confirmFailed(CenterOrderRepository.OrderRow order, String from, String reason,
                                            String idempotencyKey, Long operatorUserId) {
        if (!List.of("TIMEOUT", "PENDING").contains(from)) {
            throw BusinessException.conflict("CENTER_DISPOSITION_STATUS_INVALID",
                    "只有待处理或超时的订单才能确认失败，当前状态：" + from);
        }
        int updated = orderRepository.updateStatus(order.id(), "REJECTED", List.of("TIMEOUT", "PENDING"));
        if (updated == 0) {
            DispositionResult duplicate = replayConcurrentDisposition(order.id(), idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            throw BusinessException.conflict("CENTER_DISPOSITION_CONFLICT", "订单状态已变化，请刷新后重试");
        }
        orderRepository.insertDisposition(order.id(), "CONFIRM_FAILED", from, "REJECTED",
                BigDecimal.ZERO.setScale(2), reason, idempotencyKey, operatorUserId);
        return new DispositionResult(order.id(), "REJECTED", "CONFIRM_FAILED", BigDecimal.ZERO.setScale(2),
                null, false);
    }

    /** 已受理订单的退款/撤单：冻结额度退回可用额度，状态落 REFUNDED 或 CANCELLED。 */
    private DispositionResult releaseWithStatus(CenterOrderRepository.OrderRow order, String from,
                                                String toStatus, String action, String reason,
                                                String idempotencyKey, Long operatorUserId) {
        if (!"ACCEPTED".equals(from)) {
            throw BusinessException.conflict("CENTER_DISPOSITION_STATUS_INVALID",
                    "只有已受理的订单才能" + ("REFUND".equals(action) ? "退款" : "撤单") + "，当前状态：" + from);
        }
        agentRepository.lockById(order.centerAgentId())
                .orElseThrow(() -> BusinessException.notFound("CENTER_AGENT_NOT_FOUND", "中心账号不存在"));
        if (order.localAgentId() != null) {
            agentLedgerService.lockMachineScore(order.localAgentId());
        } else if (nz(order.machineFundedAmount()).signum() > 0) {
            throw BusinessException.conflict("CENTER_MACHINE_RESERVATION_INVALID", "中心订单缺少风险补足机器");
        }
        int updated = orderRepository.updateStatus(order.id(), toStatus, List.of("ACCEPTED"));
        if (updated == 0) {
            DispositionResult duplicate = replayConcurrentDisposition(order.id(), idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            throw BusinessException.conflict("CENTER_DISPOSITION_CONFLICT", "订单状态已变化，请刷新后重试");
        }
        BigDecimal amount = nz(order.riskAmount());
        orderRepository.insertDisposition(order.id(), action, from, toStatus, amount, reason,
                idempotencyKey, operatorUserId);
        BigDecimal centerFunded = nz(order.frozenAmount());
        if (centerFunded.signum() > 0) {
            agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_FROZEN,
                    CenterAgentService.TYPE_BET_UNFREEZE, "OUT", centerFunded,
                    ("REFUND".equals(action) ? "订单退款：" : "订单撤单：") + order.id(),
                    "CENTER_ORDER", Long.toString(order.id()),
                    "CENTER-" + action + "-OUT-" + order.id(), operatorUserId);
            agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_AVAILABLE,
                    CenterAgentService.TYPE_BET_UNFREEZE, "IN", centerFunded,
                    ("REFUND".equals(action) ? "订单退款：" : "订单撤单：") + order.id(),
                    "CENTER_ORDER", Long.toString(order.id()),
                    "CENTER-" + action + "-IN-" + order.id(), operatorUserId);
        }
        if (order.localAgentId() != null && nz(order.machineFundedAmount()).signum() > 0) {
            agentLedgerService.releaseCenterRisk(order.localAgentId(), order.machineFundedAmount(), order.id());
        }
        return new DispositionResult(order.id(), toStatus, action, amount, order.settleAmount(), false);
    }

    private DispositionResult replayConcurrentDisposition(long centerOrderId, String idempotencyKey) {
        return orderRepository.findDispositionByIdempotencyKeyForUpdate(idempotencyKey)
                .map(row -> replayDisposition(centerOrderId, row)).orElse(null);
    }

    private DispositionResult replayDisposition(long requestedOrderId,
                                                CenterOrderRepository.DispositionRow row) {
        if (row.centerOrderId() != requestedOrderId) {
            throw BusinessException.conflict("CENTER_DISPOSITION_KEY_CONFLICT",
                    "该处置幂等键已用于其他中心订单");
        }
        CenterOrderRepository.OrderRow order = orderRepository.findById(row.centerOrderId())
                .orElseThrow(() -> BusinessException.notFound("CENTER_ORDER_NOT_FOUND", "中心订单不存在"));
        return new DispositionResult(row.centerOrderId(), order.status(), row.action(),
                row.amount(), order.settleAmount(), true);
    }

    /** 某笔中心订单的处置历史。 */
    @Transactional(readOnly = true)
    public List<CenterOrderRepository.DispositionRow> dispositionHistory(long centerOrderId) {
        return orderRepository.findDispositions(centerOrderId);
    }

    /** 需要人工处置的订单（{@code TIMEOUT} / {@code PENDING}）。 */
    @Transactional(readOnly = true)
    public List<CenterOrderRepository.OrderRow> pendingDispositions(int limit) {
        return orderRepository.findByStatuses(List.of("TIMEOUT", "PENDING"), limit);
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
        agentRepository.lockById(order.centerAgentId())
                .orElseThrow(() -> BusinessException.notFound("CENTER_AGENT_NOT_FOUND", "中心账号不存在"));
        if (order.localAgentId() != null) {
            agentLedgerService.lockMachineScore(order.localAgentId());
        } else if (nz(order.machineFundedAmount()).signum() > 0) {
            throw BusinessException.conflict("CENTER_MACHINE_RESERVATION_INVALID", "中心订单缺少风险补足机器");
        }
        BigDecimal centerFunded = nz(order.frozenAmount());
        if (centerFunded.signum() > 0) {
            agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_FROZEN,
                    CenterAgentService.TYPE_BET_UNFREEZE, "OUT", centerFunded,
                    reason == null ? "撤回冻结" : reason, "CENTER_ORDER", Long.toString(centerOrderId),
                    "CENTER-UNFREEZE-OUT-" + centerOrderId, null);
            agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_AVAILABLE,
                    CenterAgentService.TYPE_BET_UNFREEZE, "IN", centerFunded,
                    reason == null ? "撤回冻结" : reason, "CENTER_ORDER", Long.toString(centerOrderId),
                    "CENTER-UNFREEZE-IN-" + centerOrderId, null);
        }
        if (order.localAgentId() != null && nz(order.machineFundedAmount()).signum() > 0) {
            agentLedgerService.releaseCenterRisk(order.localAgentId(), order.machineFundedAmount(), order.id());
        }
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
     *
     * <p><b>限额取代理级优先、全局兜底</b>（P1-C1）：{@code center_agent.max_single_amount}
     * 与 {@code max_issue_exposure} 为 {@code null} 时才用全局常量。这样既支持按代理差异化管理，
     * 又保持与阶段 B 完全兼容（存量代理两列为 NULL，行为不变）。</p>
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
        BigDecimal singleLimit = agent.maxSingleAmount() != null ? agent.maxSingleAmount() : MAX_SINGLE_AMOUNT;
        if (normalizedAmount.compareTo(singleLimit) > 0) {
            return "CENTER_AMOUNT_LIMIT_EXCEEDED";
        }
        if (odds == null || odds.compareTo(BigDecimal.ONE) < 0) {
            return "CENTER_ODDS_INVALID";
        }
        return null;
    }

    /**
     * 异常行为识别（P1-C1）：短时高频被拒。
     *
     * <p>为什么用"被拒次数"而不是"下单次数"：正常代理下单不会频繁被拒；
     * 短时间内反复撞限额/敞口/额度，是"试探系统边界"的典型信号。
     * 这里用滚动窗口内的命中次数做判定，阈值可配。</p>
     *
     * @return 命中时返回拒绝码，否则 {@code null}
     */
    private String detectAbnormalBehavior(CenterAgentRepository.AgentRow agent, String agentOrderNo) {
        if (behaviorWindow.isZero() || behaviorHitThreshold <= 0) {
            return null;
        }
        Instant since = Instant.now().minus(behaviorWindow);
        long hits = agentRepository.countRiskHitsSince(agent.id(), since);
        if (hits >= behaviorHitThreshold) {
            log.warn("中心风控命中：短时高频试探 agentId={} hits={} window={} orderNo={}",
                    agent.id(), hits, behaviorWindow, agentOrderNo);
            return "CENTER_BEHAVIOR_RATE_ANOMALY";
        }
        return null;
    }

    /**
     * 落一条风控命中记录。
     *
     * <p>记录**观测值与阈值**而不只是"被拒了"：事后复核时能直接回答"当时超了多少"，
     * 否则只能靠猜。类别用于管理端分组统计。</p>
     */
    private void recordRiskHit(CenterAgentRepository.AgentRow agent, String ruleCode, String gameCode,
                               String issueNumber, String agentOrderNo, BigDecimal amount) {
        String category = switch (ruleCode) {
            case "CENTER_AMOUNT_LIMIT_EXCEEDED", "CENTER_AMOUNT_INVALID" -> "LIMIT";
            case "CENTER_ISSUE_EXPOSURE_EXCEEDED" -> "EXPOSURE";
            case "CENTER_AVAILABLE_INSUFFICIENT", "CENTER_RISK_CAPACITY_INSUFFICIENT" -> "ACCOUNT";
            case "CENTER_BEHAVIOR_RATE_ANOMALY" -> "BEHAVIOR";
            default -> "LIMIT";
        };
        BigDecimal threshold = switch (ruleCode) {
            case "CENTER_AMOUNT_LIMIT_EXCEEDED" ->
                    agent.maxSingleAmount() != null ? agent.maxSingleAmount() : MAX_SINGLE_AMOUNT;
            case "CENTER_ISSUE_EXPOSURE_EXCEEDED" ->
                    agent.maxIssueExposure() != null ? agent.maxIssueExposure() : MAX_ISSUE_EXPOSURE;
            case "CENTER_AVAILABLE_INSUFFICIENT" -> agent.availableBalance();
            case "CENTER_RISK_CAPACITY_INSUFFICIENT" -> null;
            default -> null;
        };
        agentRepository.recordRiskHit(agent.id(), ruleCode, category, amount, threshold,
                gameCode, issueNumber, agentOrderNo, rejectReason(ruleCode));
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
            case "CENTER_RISK_CAPACITY_INSUFFICIENT" -> "中心可用额度和当前机器积分合计不足以覆盖最大净赔付风险";
            case "CENTER_RISK_SNAPSHOT_INVALID" -> "本地注单结算快照无法计算最大净赔付风险";
            case "CENTER_LOCAL_BET_REQUIRED" -> "中心接单必须关联本地注单和报单机器";
            case "CENTER_BEHAVIOR_RATE_ANOMALY" -> "短时高频被拒，已触发异常行为风控";
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

    /** 一次订单处置的结果。 */
    public record DispositionResult(long orderId, String status, String action, BigDecimal frozenAmount,
                                    BigDecimal settleAmount, boolean duplicate) {
    }
}
