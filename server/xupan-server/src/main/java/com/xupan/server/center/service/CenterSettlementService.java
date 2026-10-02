package com.xupan.server.center.service;

import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.repository.CenterOrderRepository;
import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import com.xupan.server.game.service.SettlementResult;
import com.xupan.server.game.service.SettlementService;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 中心上游第二层结算：开奖后中心按结果赔给代理或从代理收（虚拟积分）。
 *
 * <p>对应《代理报网盘口与结算业务规则说明》§10（两层账）：
 * 玩家赢 → 本地赔玩家，中心再赔代理；玩家输 → 本地收玩家，中心再从代理收。
 * 上报的目的是把代理的风险转移一部分到上游，而不是让上游直接给玩家结算。</p>
 *
 * <p>额度流转（账本原则 1，三层各是不同事实）：
 * <pre>
 *   接单       可用 → 冻结
 *   开奖       冻结 → 上游未结算
 *   结算       上游未结算 → 可用（代理赢：中心赔出；代理输：中心收走本金）
 * </pre></p>
 *
 * <p>幂等（原则 6）：只处理 {@code status = ACCEPTED} 的订单，结算后置为 {@code SETTLED}，
 * 因此重复调用不会重复赔钱。</p>
 *
 * <p>合规：全部为虚拟积分与内部风险敞口，不涉及任何真实资金。</p>
 */
@Service
public class CenterSettlementService {

    private final CenterOrderRepository orderRepository;
    private final CenterAgentRepository agentRepository;
    private final CenterAgentService agentService;
    private final SettlementService settlementService;

    public CenterSettlementService(CenterOrderRepository orderRepository,
                                   CenterAgentRepository agentRepository,
                                   CenterAgentService agentService,
                                   SettlementService settlementService) {
        this.orderRepository = orderRepository;
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.settlementService = settlementService;
    }

    /**
     * 结算某游戏某期的全部已接受订单。
     *
     * @param drawNumbers 开奖号码（落库快照，便于对账时不必回查开奖表）
     * @return 结算汇总
     */
    @Transactional
    public SettlementSummary settleIssue(String gameCode, String issueNumber, BallResult result,
                                         String drawNumbers, int limit) {
        if (result == null) {
            throw BusinessException.badRequest("CENTER_DRAW_RESULT_REQUIRED", "开奖结果不能为空");
        }
        List<CenterOrderRepository.OrderRow> orders =
                orderRepository.findAcceptedByIssue(gameCode, issueNumber, limit);
        List<SettledOrder> settled = new ArrayList<>();
        int skipped = 0;
        BigDecimal agentWinTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal agentLoseTotal = BigDecimal.ZERO.setScale(2);

        for (CenterOrderRepository.OrderRow order : orders) {
            SettledOrder one = settleOrder(order, result, drawNumbers);
            if (one == null) {
                skipped++;
                continue;
            }
            settled.add(one);
            if ("AGENT_WIN".equals(one.settleResult())) {
                agentWinTotal = agentWinTotal.add(one.settleAmount());
            } else if ("AGENT_LOSE".equals(one.settleResult())) {
                agentLoseTotal = agentLoseTotal.add(one.settleAmount());
            }
        }
        return new SettlementSummary(gameCode, issueNumber, settled.size(), skipped,
                agentWinTotal, agentLoseTotal, settled);
    }

    /**
     * 结算单笔中心订单。
     *
     * <p>返回 {@code null} 表示该单不是 {@code ACCEPTED}（已被别处结算或撤回），跳过而不报错，
     * 这样批量结算不会因为个别订单状态已变而整体失败。</p>
     */
    @Transactional
    public SettledOrder settleOrder(CenterOrderRepository.OrderRow order, BallResult result,
                                    String drawNumbers) {
        if (!"ACCEPTED".equals(order.status())) {
            return null;
        }
        // 中心侧只认玩法代码与参数；解析或参数校验失败按"无法结算"处理，
        // 跳过并留给人工，绝不放任它把整期结算打断，也绝不静默赔钱。
        PlayType playType;
        List<Integer> parameters;
        SettlementResult outcome;
        try {
            playType = PlayType.valueOf(order.playType().trim().toUpperCase(java.util.Locale.ROOT));
            parameters = parseParameters(order.playText());
            outcome = settlementService.settle(playType, parameters, order.amount(),
                    normalizeOdds(order.odds()), result);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        SettlementStatus status = outcome.status();

        // 先锁代理行，再动额度，避免并发结算把同一笔额度用两次
        agentRepository.lockById(order.centerAgentId());
        String settleResult;
        BigDecimal settleAmount;
        String entryType;
        switch (status) {
            case WIN -> {
                // 代理赢：中心赔出 = 本金 × (赔率 - 1)，从上游未结算划回可用
                settleResult = "AGENT_WIN";
                settleAmount = outcome.netProfit().abs();
                entryType = CenterAgentService.TYPE_SETTLE_WIN;
            }
            case LOSE -> {
                // 代理输：中心收走本金（本金已在冻结/未结算里，直接清零）
                settleResult = "AGENT_LOSE";
                settleAmount = order.amount();
                entryType = CenterAgentService.TYPE_SETTLE_LOSE;
            }
            default -> {
                // DRAW / CANCELED / PENDING：流局，本金原额退回可用
                settleResult = "VOID";
                settleAmount = order.amount();
                entryType = CenterAgentService.TYPE_SETTLE_REFUND;
            }
        }

        // 冻结 → 上游未结算（只在冻结仍有余额时移动，避免重复移动）
        if (order.frozenAmount().signum() > 0) {
            agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_FROZEN,
                    CenterAgentService.TYPE_UPSTREAM_MOVE, "OUT", order.frozenAmount(),
                    "开奖转未结算：期号 " + order.issueNumber(), "CENTER_ORDER", Long.toString(order.id()),
                    "CENTER-UNSETTLED-OUT-" + order.id(), null);
            agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_UPSTREAM_UNSETTLED,
                    CenterAgentService.TYPE_UPSTREAM_MOVE, "IN", order.frozenAmount(),
                    "开奖转未结算：期号 " + order.issueNumber(), "CENTER_ORDER", Long.toString(order.id()),
                    "CENTER-UNSETTLED-IN-" + order.id(), null);
        }

        // 未结算 → 可用。
        //
        // 语义要点（容易写错的地方）：
        //   * 代理赢：中心赔出净额 settleAmount，从"未结算"划出、进"可用"；
        //     本金部分是代理自己的钱，本来就在未结算里，不进可用（否则等于中心连本金一起送）。
        //   * 代理输：本金在接单时就已从可用划走，输掉只是"未结算层的本金归中心"，
        //     **不能再动可用额度**——再扣一次就是把同一笔本金扣两遍（本用例曾在真实运行中暴露此问题）。
        //   * 流局：本金原额退回可用。
        agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_UPSTREAM_UNSETTLED,
                entryType, "OUT", order.amount(),
                "第二层结算：期号 " + order.issueNumber() + " " + settleResult, "CENTER_ORDER",
                Long.toString(order.id()), "CENTER-SETTLE-UNSETTLED-" + order.id(), null);
        if ("AGENT_WIN".equals(settleResult)) {
            agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_AVAILABLE,
                    entryType, "IN", settleAmount,
                    "第二层结算：期号 " + order.issueNumber() + " 中心赔出", "CENTER_ORDER",
                    Long.toString(order.id()), "CENTER-SETTLE-AVAILABLE-" + order.id(), null);
        } else if ("VOID".equals(settleResult)) {
            agentService.moveInternal(order.centerAgentId(), CenterAgentService.BUCKET_AVAILABLE,
                    entryType, "IN", order.amount(),
                    "第二层结算：期号 " + order.issueNumber() + " 流局退回本金", "CENTER_ORDER",
                    Long.toString(order.id()), "CENTER-SETTLE-AVAILABLE-" + order.id(), null);
        }
        // 代理输：可用额度不再变动（本金已在接单时离开可用）

        int updated = orderRepository.settle(order.id(), settleResult, settleAmount, drawNumbers,
                Instant.now());
        if (updated != 1) {
            // 状态已被别处改掉：抛出让事务回滚，避免"额度动了但订单没结算"
            throw BusinessException.conflict("CENTER_ORDER_SETTLE_CONFLICT", "中心订单状态已变化，结算未生效");
        }
        return new SettledOrder(order.id(), order.centerAgentId(), order.agentOrderNo(), settleResult,
                settleAmount, order.amount(), settleResultLabel(settleResult));
    }

    /**
     * 把赔率归一到结算侧接受的精度（最多 3 位小数）。
     *
     * <p>为什么需要：{@code center_order.odds} 是 {@code DECIMAL(12,4)}，从库里读出来是
     * {@code 3.8500}（scale=4），而 {@link SettlementService#settle} 明确要求 scale ≤ 3。
     * 不归一就会在结算时抛"赔率必须是不小于 1 的金额，最多三位小数"，
     * 让一期的订单全部结不掉。这是层间契约不一致，不是数据错误，所以在边界处统一。</p>
     */
    static BigDecimal normalizeOdds(BigDecimal odds) {
        if (odds == null) {
            return null;
        }
        BigDecimal trimmed = odds.stripTrailingZeros();
        return trimmed.scale() < 0 ? trimmed.setScale(0) : trimmed;
    }

    /**
     * 解析玩法参数。
     *
     * <p>中心侧约定：参数以逗号分隔存在 {@code play_text} 的 {@code params=} 段里
     * （例如 {@code "番摊 1;params=1"}）；没有该段时按空参数处理，
     * 由 {@link SettlementService} 的校验决定是否可结算。</p>
     */
    static List<Integer> parseParameters(String playText) {
        if (playText == null || playText.isBlank()) {
            return List.of();
        }
        int index = playText.indexOf("params=");
        if (index < 0) {
            return List.of();
        }
        String raw = playText.substring(index + "params=".length()).trim();
        int end = raw.indexOf(';');
        if (end >= 0) {
            raw = raw.substring(0, end);
        }
        if (raw.isBlank()) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(Integer.parseInt(trimmed));
            }
        }
        return values;
    }

    private static String settleResultLabel(String settleResult) {
        return switch (settleResult) {
            case "AGENT_WIN" -> "代理赢，中心赔出";
            case "AGENT_LOSE" -> "代理输，中心收走本金";
            default -> "流局，本金原额退回";
        };
    }

    /** 单笔结算结果。 */
    public record SettledOrder(long orderId, long centerAgentId, String agentOrderNo, String settleResult,
                               BigDecimal settleAmount, BigDecimal stake, String label) {
    }

    /** 一期结算汇总。 */
    public record SettlementSummary(String gameCode, String issueNumber, int settledCount, int skippedCount,
                                    BigDecimal agentWinTotal, BigDecimal agentLoseTotal,
                                    List<SettledOrder> orders) {
    }
}
