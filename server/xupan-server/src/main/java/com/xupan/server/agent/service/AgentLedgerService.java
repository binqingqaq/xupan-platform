package com.xupan.server.agent.service;

import com.xupan.server.agent.repository.AgentLedgerRepository;
import com.xupan.server.web.BusinessException;
import com.xupan.server.web.MoneyAmounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 代理层账本：机器与子账号积分的**唯一写入方**。
 *
 * <p>对应账本原则 2、3（见 {@code docs/需求基线/2026-10-02-中心上游准入基线与账本原则现状.md}）：
 * 任何余额变化都必须同时写一条追加式流水。因此本类之外不允许再直接
 * {@code UPDATE agent SET score=...} / {@code UPDATE agent_group SET score=...}。</p>
 *
 * <p>并发与幂等：</p>
 * <ul>
 *   <li>先 {@code SELECT ... FOR UPDATE} 锁行，再算差额、写余额、写流水，三步在同一事务同一把锁内；</li>
 *   <li>{@code idempotencyKey} 命中已有流水时按重放处理：参数一致则直接返回原结果，不一致报 409。</li>
 * </ul>
 *
 * <p>合规：只处理虚拟积分，不涉及任何真实资金。</p>
 */
@Service
public class AgentLedgerService {

    /** 机器流水类型。 */
    public static final String TYPE_MACHINE_INIT = "MACHINE_INIT";
    public static final String TYPE_MACHINE_ADJUST = "MACHINE_ADJUST";
    public static final String TYPE_SUB_ACCOUNT_INIT = "SUB_ACCOUNT_INIT";
    public static final String TYPE_SUB_ACCOUNT_ADJUST = "SUB_ACCOUNT_ADJUST";
    /** 代理给玩家上分（代理积分减少）。 */
    public static final String TYPE_PLAYER_TOP_UP = "PLAYER_TOP_UP";
    /** 代理从玩家下分（代理积分增加）。 */
    public static final String TYPE_PLAYER_DOWN = "PLAYER_DOWN";
    /** 子账号 → 机器：按 BY220 语义从子账号资金池划给机器（子账号 OUT / 机器 IN）。 */
    public static final String TYPE_MACHINE_ALLOCATE = "MACHINE_ALLOCATE";
    /** 机器 → 子账号：机器积分退回子账号资金池（机器 OUT / 子账号 IN）。 */
    public static final String TYPE_MACHINE_RETURN = "MACHINE_RETURN";
    /**
     * 删除玩家时把其**删除时余额**退回机器池（机器 IN）。
     *
     * <p>BY220 {@code application/rbt/controller/Index.php:239} 直接
     * {@code db('robot')->setInc('score', $user['score'])}，**不记任何流水**。
     * 本项目账本原则 2 要求"余额变化必须同时写流水"，所以这里多记一条——
     * 相对 BY220 是**超集**，已在实施报告与缺口表中如实登记。</p>
     */
    public static final String TYPE_PLAYER_DELETE_REFUND = "PLAYER_DELETE_REFUND";

    /**
     * 机器池不足时的**机器端**文案：原文照抄 BY220
     * （{@code application/rbt/controller/Index.php:618-620}）——那是机器账号给自己名下玩家上分时的自述口吻，
     * 平移必须保持原样。
     */
    public static final String INSUFFICIENT_MACHINE_SCORE_MESSAGE = "您的账号剩余积分不足以完成本次上分！";

    /**
     * 机器池不足时的**平台侧**文案（{@code GLOBAL} 超管 / {@code GROUP} 子账号给玩家上下分）。
     *
     * <p>BY220 没有"平台侧直接给玩家发分"这条链路，没有原文可抄；若沿用机器端那句，
     * "您的账号"会被读成**操作者**（超管），而实际被扣的是**玩家归属的那台机器**，
     * 容易让人以为扣错了账号。错误码两条路径一致，都是 {@code AGENT_SCORE_INSUFFICIENT}，
     * 协议不变（见计划 §3.5「文案口径」，工头 2026-10-08 补充）。</p>
     */
    public static final String INSUFFICIENT_OWNER_MACHINE_SCORE_MESSAGE =
            "该玩家所属机器的积分不足以完成本次上分！";

    private final AgentLedgerRepository ledger;

    public AgentLedgerService(AgentLedgerRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * 按差额调整机器积分（代理上下分用）。
     *
     * @param delta 正数表示增加、负数表示减少
     * @return 变动后的机器积分
     * @throws BusinessException 400 {@code MACHINE_SCORE_INVALID}：金额超过两位小数
     * @throws BusinessException 409 {@code AGENT_SCORE_INSUFFICIENT}：余额不足以扣减
     */
    @Transactional
    public BigDecimal changeMachineScore(long agentId, BigDecimal delta, String entryType, String reason,
                                         String referenceType, String referenceId,
                                         Long operatorUserId, String idempotencyKey) {
        return changeMachineScore(agentId, delta, entryType, reason, referenceType, referenceId,
                operatorUserId, idempotencyKey, INSUFFICIENT_MACHINE_SCORE_MESSAGE);
    }

    /**
     * 同 {@link #changeMachineScore}，但可指定机器池不足时的文案。
     *
     * <p>平台侧（{@code GLOBAL}/{@code GROUP}）给玩家上下分时，被扣的是**玩家归属机器**而不是操作者自己的账号，
     * 因此不能用 BY220 那句自述口吻，见 {@link #INSUFFICIENT_OWNER_MACHINE_SCORE_MESSAGE}。
     * 错误码两条路径都是 {@code AGENT_SCORE_INSUFFICIENT}。</p>
     */
    @Transactional
    public BigDecimal changeMachineScore(long agentId, BigDecimal delta, String entryType, String reason,
                                         String referenceType, String referenceId,
                                         Long operatorUserId, String idempotencyKey,
                                         String insufficientMessage) {
        BigDecimal before = ledger.lockAgentScore(agentId)
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器账号不存在"));
        BigDecimal normalizedDelta = requireDelta(delta, "MACHINE_SCORE_INVALID",
                "机器积分格式无效", "机器积分最多两位小数");
        Optional<AgentLedgerRepository.LedgerRow> replay = ledger.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            AgentLedgerRepository.LedgerRow row = replay.get();
            String expectedDirection = normalizedDelta.signum() > 0 ? "IN" : "OUT";
            if (row.agentId() == null || row.agentId() != agentId
                    || row.amount().compareTo(MoneyAmounts.rounded(normalizedDelta.abs())) != 0
                    || !expectedDirection.equals(row.direction())) {
                throw BusinessException.conflict("AGENT_LEDGER_IDEMPOTENCY_CONFLICT", "账本幂等键参数不一致");
            }
            return row.balanceAfter();
        }
        BigDecimal after = MoneyAmounts.rounded(before.add(normalizedDelta));
        return applyMachine(agentId, before, after, entryType, reason, referenceType, referenceId,
                operatorUserId, idempotencyKey, insufficientMessage);
    }

    /**
     * 把机器积分设置为指定值（超管编辑机器用）。
     *
     * <p>目标值等于当前值时**不写流水**：没有变动就没有账目，避免制造无意义的账本噪音。
     * 这也让重复提交同一个编辑请求天然幂等。</p>
     *
     * @return 变动后的机器积分
     */
    @Transactional
    public BigDecimal setMachineScore(long agentId, BigDecimal target, String entryType, String reason,
                                      Long operatorUserId, String idempotencyKey) {
        BigDecimal before = ledger.lockAgentScore(agentId)
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器账号不存在"));
        BigDecimal after = targetScore(target, "MACHINE_SCORE_INVALID",
                "机器积分最多两位小数", "机器积分不能为负数");
        if (before.compareTo(after) == 0) {
            return after;
        }
        return applyMachine(agentId, before, after, entryType, reason, "MACHINE", Long.toString(agentId),
                operatorUserId, idempotencyKey, INSUFFICIENT_MACHINE_SCORE_MESSAGE);
    }

    /**
     * 按差额调整子账号积分（超管给子账号上分/下分用）。
     *
     * <p>BY220 {@code Users.php::add()} 编辑子账号时：{@code if (!empty($data['up'])) setInc('score', up)}；
     * {@code if (!empty($data['down']))} 先校验 {@code $admin['score'] < $data['down']}，不足则直接返回
     * 「该子帐号积分不足以下分！」，否则 {@code setDec('score', down)}。本方法用同一个入口承载两个方向：
     * {@code delta > 0} 为上分、{@code delta < 0} 为下分。</p>
     *
     * <p>语义与 {@link #changeMachineScore} 对齐：{@code SELECT ... FOR UPDATE} 锁子账号行 → 算差额 →
     * 写余额 → 写流水，同一事务同一把锁。{@code delta} 为 0 或 {@code null} 时视为"没有变动"，
     * **不写流水**（与 BY220 用 {@code !empty} 判定 0 等于未填一致）；{@code idempotencyKey} 命中已有流水时
     * 按重放处理，参数不一致报 409。</p>
     *
     * @param delta 正数表示上分、负数表示下分
     * @return 变动后的子账号积分
     * @throws BusinessException 400 {@code SUB_ACCOUNT_SCORE_INVALID}：金额超过两位小数
     * @throws BusinessException 409 {@code SUB_ACCOUNT_SCORE_DOWN_INSUFFICIENT}：余额不足以扣减（下分超额）
     */
    @Transactional
    public BigDecimal changeGroupScore(long groupId, BigDecimal delta, String entryType, String reason,
                                       Long operatorUserId, String idempotencyKey) {
        BigDecimal before = ledger.lockGroupScore(groupId)
                .orElseThrow(() -> BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在"));
        if (delta == null || delta.signum() == 0) {
            // 与 BY220 用 !empty 判定 0 等于未填一致：没有变动就不写流水。
            return before;
        }
        BigDecimal normalizedDelta = requireDelta(delta, "SUB_ACCOUNT_SCORE_INVALID",
                "子账号积分格式无效", "子账号积分最多两位小数");
        BigDecimal amount = MoneyAmounts.rounded(normalizedDelta.abs());
        Optional<AgentLedgerRepository.LedgerRow> replay = ledger.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            AgentLedgerRepository.LedgerRow row = replay.get();
            String expectedDirection = normalizedDelta.signum() > 0 ? "IN" : "OUT";
            if (row.groupId() == null || row.groupId() != groupId
                    || row.amount().compareTo(amount) != 0
                    || !expectedDirection.equals(row.direction())) {
                throw BusinessException.conflict("AGENT_LEDGER_IDEMPOTENCY_CONFLICT", "账本幂等键参数不一致");
            }
            return row.balanceAfter();
        }
        BigDecimal after = MoneyAmounts.rounded(before.add(normalizedDelta));
        if (after.signum() < 0) {
            // 文案严格对齐 BY220 Users.php 第 67 行的「该子帐号积分不足以下分！」；
            // 抛在写余额之前，保证下分超额时不产生任何余额变动。
            throw BusinessException.conflict("SUB_ACCOUNT_SCORE_DOWN_INSUFFICIENT", "该子帐号积分不足以下分！");
        }
        if (ledger.updateGroupScore(groupId, after) != 1) {
            throw BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在");
        }
        ledger.insertEntry(null, groupId, entryType, normalizedDelta.signum() > 0 ? "IN" : "OUT", amount, before,
                after, reason, "GROUP", Long.toString(groupId), idempotencyKey, operatorUserId,
                java.time.Instant.now());
        return after;
    }

    /** 把子账号积分设置为指定值（超管编辑子账号用）。 */
    @Transactional
    public BigDecimal setGroupScore(long groupId, BigDecimal target, String entryType, String reason,
                                    Long operatorUserId, String idempotencyKey) {
        BigDecimal before = ledger.lockGroupScore(groupId)
                .orElseThrow(() -> BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在"));
        BigDecimal after = targetScore(target, "SUB_ACCOUNT_SCORE_INVALID",
                "子账号积分最多两位小数", "子账号积分不能为负数");
        if (before.compareTo(after) == 0) {
            return after;
        }
        String direction = after.compareTo(before) > 0 ? "IN" : "OUT";
        BigDecimal amount = MoneyAmounts.rounded(after.subtract(before).abs());
        if (ledger.updateGroupScore(groupId, after) != 1) {
            throw BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在");
        }
        ledger.insertEntry(null, groupId, entryType, direction, amount, before, after, reason,
                "GROUP", Long.toString(groupId), idempotencyKey, operatorUserId, java.time.Instant.now());
        return after;
    }

    /**
     * 设定机器积分，并按 BY220 语义与子账号资金池对账划拨。
     *
     * <p>BY220 里子账号（{@code x_admin.score}）就是名下机器的上游资金池：{@code Rb.php} 子账号建机器时
     * {@code setDec('score')} 扣子账号积分；{@code common.php::rbUpdown} 给机器上分时
     * {@code admin.setDec} + {@code robot.setInc}，下分时反向；超管直接建的机器没有子账号（{@code uid} 为空），
     * 不涉及资金池。本方法把「机器目标积分」翻译成：①归属变化的先把机器现有积分退回旧池；
     * ②再从新池划出差额（或把差额退回池）。</p>
     *
     * <p>加锁顺序固定为「子账号（按 id 升序）→ 机器」，与其它账本路径保持同一顺序，避免交叉等待死锁。</p>
     *
     * @param fromGroupId 机器原来的子账号，可空；新建机器传 {@code null}
     * @param toGroupId   本次编辑后的子账号，可空（无池时按超管直连机器处理）
     * @param target      机器目标积分
     * @param directEntryType 无资金池时的流水类型（{@code MACHINE_INIT} / {@code MACHINE_ADJUST}）
     * @throws BusinessException 400 {@code MACHINE_SCORE_INVALID}：目标值超过两位小数或为负数
     * @throws BusinessException 409 {@code SUB_ACCOUNT_SCORE_INSUFFICIENT}：子账号池余额不足以划出
     */
    @Transactional
    public BigDecimal assignMachineScore(long agentId, Long fromGroupId, Long toGroupId, BigDecimal target,
                                         String directEntryType, String reason, Long operatorUserId) {
        BigDecimal normalizedTarget = targetScore(target, "MACHINE_SCORE_INVALID",
                "机器积分最多两位小数", "机器积分不能为负数");
        java.util.TreeSet<Long> groupIds = new java.util.TreeSet<>();
        if (fromGroupId != null) {
            groupIds.add(fromGroupId);
        }
        if (toGroupId != null) {
            groupIds.add(toGroupId);
        }
        java.util.Map<Long, BigDecimal> groupBalances = new java.util.HashMap<>();
        for (Long groupId : groupIds) {
            groupBalances.put(groupId, ledger.lockGroupScore(groupId)
                    .orElseThrow(() -> BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在")));
        }
        BigDecimal machineBalance = ledger.lockAgentScore(agentId)
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器账号不存在"));
        String reasonText = reason == null || reason.isBlank() ? "超管维护机器积分" : reason;

        // ① 归属变化：机器现有积分先退回原池
        if (fromGroupId != null && !fromGroupId.equals(toGroupId) && machineBalance.signum() > 0) {
            machineBalance = transfer(groupBalances, fromGroupId, agentId, machineBalance,
                    machineBalance.negate(), TYPE_MACHINE_RETURN, "机器改挂子账号：积分退回原池", operatorUserId);
        }
        // ② 有资金池：按差额划拨（正数=池给机器，负数=机器退回池）
        if (toGroupId != null) {
            BigDecimal delta = normalizedTarget.subtract(machineBalance);
            if (delta.signum() != 0) {
                machineBalance = transfer(groupBalances, toGroupId, agentId, machineBalance, delta,
                        delta.signum() > 0 ? TYPE_MACHINE_ALLOCATE : TYPE_MACHINE_RETURN,
                        reasonText, operatorUserId);
            }
            return machineBalance;
        }
        // ③ 无资金池（BY220 超管自建机器）：保持"直接设定"语义
        return setMachineScore(agentId, normalizedTarget,
                directEntryType == null ? TYPE_MACHINE_ADJUST : directEntryType,
                reasonText, operatorUserId, "MACHINE-ADJUST-" + java.util.UUID.randomUUID());
    }

    /**
     * 子账号池与机器之间的一次划拨。
     *
     * <p>{@code delta > 0} 表示池 → 机器（池扣、机器加）；{@code delta < 0} 表示机器 → 池。
     * 两侧各写一条追加式流水（同一 {@code entryType}，方向相反），并把更新后的池余额写回 {@code groupBalances}。</p>
     *
     * @return 划拨后的机器积分
     */
    private BigDecimal transfer(java.util.Map<Long, BigDecimal> groupBalances, long groupId, long agentId,
                                BigDecimal machineBalance, BigDecimal delta, String entryType,
                                String reason, Long operatorUserId) {
        BigDecimal amount = MoneyAmounts.rounded(delta.abs());
        BigDecimal groupBefore = groupBalances.get(groupId);
        BigDecimal groupAfter;
        BigDecimal machineAfter;
        if (delta.signum() > 0) {
            groupAfter = groupBefore.subtract(amount);
            if (groupAfter.signum() < 0) {
                // 文案对齐 BY220「该子帐号积分不足以下分！」的语气
                throw BusinessException.conflict("SUB_ACCOUNT_SCORE_INSUFFICIENT",
                        "该子帐号积分不足以为机器分配积分！");
            }
            machineAfter = machineBalance.add(amount);
        } else {
            machineAfter = machineBalance.subtract(amount);
            if (machineAfter.signum() < 0) {
                throw BusinessException.conflict("MACHINE_SCORE_INSUFFICIENT", "机器积分不足以退回子账号");
            }
            groupAfter = groupBefore.add(amount);
        }
        if (ledger.updateGroupScore(groupId, groupAfter) != 1) {
            throw BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在");
        }
        if (ledger.updateAgentScore(agentId, machineAfter) != 1) {
            throw BusinessException.notFound("MACHINE_NOT_FOUND", "机器账号不存在");
        }
        String key = entryType + "-" + groupId + "-" + agentId + "-" + java.util.UUID.randomUUID();
        ledger.insertEntry(null, groupId, entryType, delta.signum() > 0 ? "OUT" : "IN", amount,
                groupBefore, groupAfter, reason, "MACHINE_ALLOCATION", Long.toString(agentId),
                key + ":GROUP", operatorUserId, java.time.Instant.now());
        ledger.insertEntry(agentId, null, entryType, delta.signum() > 0 ? "IN" : "OUT", amount,
                machineBalance, machineAfter, reason, "MACHINE_ALLOCATION", Long.toString(groupId),
                key + ":MACHINE", operatorUserId, java.time.Instant.now());
        groupBalances.put(groupId, groupAfter);
        return machineAfter;
    }

    public List<AgentLedgerRepository.LedgerRow> machineEntries(long agentId, int page, int pageSize) {
        return ledger.findAgentEntries(agentId, page, pageSize);
    }

    public long countMachineEntries(long agentId) {
        return ledger.countAgentEntries(agentId);
    }

    /** 机器账本是否与余额一致（对账用）。 */
    public boolean machineLedgerMatchesBalance(long agentId) {
        return ledger.agentLedgerMatchesBalance(agentId);
    }

    private BigDecimal applyMachine(long agentId, BigDecimal before, BigDecimal after, String entryType,
                                    String reason, String referenceType, String referenceId,
                                    Long operatorUserId, String idempotencyKey, String insufficientMessage) {
        if (after.signum() < 0) {
            throw BusinessException.conflict("AGENT_SCORE_INSUFFICIENT",
                    insufficientMessage == null ? INSUFFICIENT_MACHINE_SCORE_MESSAGE : insufficientMessage);
        }
        if (ledger.updateAgentScore(agentId, after) != 1) {
            throw BusinessException.notFound("MACHINE_NOT_FOUND", "机器账号不存在");
        }
        String direction = after.compareTo(before) > 0 ? "IN" : "OUT";
        BigDecimal amount = MoneyAmounts.rounded(after.subtract(before).abs());
        ledger.insertEntry(agentId, null, entryType, direction, amount, before, after, reason,
                referenceType, referenceId, idempotencyKey, operatorUserId, java.time.Instant.now());
        return after;
    }

    /**
     * 机器/子账号**目标值**的严格校验：超精度 400，负数 400，{@code null} 按 0 处理
     * （与修复前一致，调用方可以"不传积分"表示 0）。
     */
    private static BigDecimal targetScore(BigDecimal target, String code, String scaleMessage,
                                          String negativeMessage) {
        if (target == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal normalized = MoneyAmounts.requireTwoDecimals(target, code, negativeMessage, scaleMessage);
        if (normalized.signum() < 0) {
            throw BusinessException.badRequest(code, negativeMessage);
        }
        return normalized;
    }

    /**
     * 机器/子账号**增量（delta）**的严格校验。
     *
     * <p>与 {@link #targetScore} 的区别：delta 本身带符号（负数是合法的下分/扣减），
     * 所以这里只做"最多两位小数"的收口；{@code null} 交给调用方按"没有变动"处理。</p>
     */
    private static BigDecimal requireDelta(BigDecimal delta, String code,
                                           String invalidMessage, String scaleMessage) {
        return MoneyAmounts.requireTwoDecimals(delta, code, invalidMessage, scaleMessage);
    }
}
