package com.xupan.server.agent.service;

import com.xupan.server.agent.repository.AgentLedgerRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private final AgentLedgerRepository ledger;

    public AgentLedgerService(AgentLedgerRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * 按差额调整机器积分（代理上下分用）。
     *
     * @param delta 正数表示增加、负数表示减少
     * @return 变动后的机器积分
     * @throws BusinessException 409 {@code AGENT_SCORE_INSUFFICIENT}：余额不足以扣减
     */
    @Transactional
    public BigDecimal changeMachineScore(long agentId, BigDecimal delta, String entryType, String reason,
                                         String referenceType, String referenceId,
                                         Long operatorUserId, String idempotencyKey) {
        BigDecimal before = ledger.lockAgentScore(agentId)
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器账号不存在"));
        Optional<AgentLedgerRepository.LedgerRow> replay = ledger.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            AgentLedgerRepository.LedgerRow row = replay.get();
            String expectedDirection = delta.signum() > 0 ? "IN" : "OUT";
            if (row.agentId() == null || row.agentId() != agentId
                    || row.amount().compareTo(delta.abs().setScale(2, RoundingMode.HALF_UP)) != 0
                    || !expectedDirection.equals(row.direction())) {
                throw BusinessException.conflict("AGENT_LEDGER_IDEMPOTENCY_CONFLICT", "账本幂等键参数不一致");
            }
            return row.balanceAfter();
        }
        BigDecimal after = before.add(delta).setScale(2, RoundingMode.HALF_UP);
        return applyMachine(agentId, before, after, entryType, reason, referenceType, referenceId,
                operatorUserId, idempotencyKey);
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
        BigDecimal after = scaled(target, "MACHINE_SCORE_INVALID", "机器积分不能为负数");
        if (before.compareTo(after) == 0) {
            return after;
        }
        return applyMachine(agentId, before, after, entryType, reason, "MACHINE", Long.toString(agentId),
                operatorUserId, idempotencyKey);
    }

    /** 把子账号积分设置为指定值（超管编辑子账号用）。 */
    @Transactional
    public BigDecimal setGroupScore(long groupId, BigDecimal target, String entryType, String reason,
                                    Long operatorUserId, String idempotencyKey) {
        BigDecimal before = ledger.lockGroupScore(groupId)
                .orElseThrow(() -> BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在"));
        BigDecimal after = scaled(target, "SUB_ACCOUNT_SCORE_INVALID", "子账号积分不能为负数");
        if (before.compareTo(after) == 0) {
            return after;
        }
        String direction = after.compareTo(before) > 0 ? "IN" : "OUT";
        BigDecimal amount = after.subtract(before).abs();
        if (ledger.updateGroupScore(groupId, after) != 1) {
            throw BusinessException.notFound("SUB_ACCOUNT_NOT_FOUND", "子账号不存在");
        }
        ledger.insertEntry(null, groupId, entryType, direction, amount, before, after, reason,
                "GROUP", Long.toString(groupId), idempotencyKey, operatorUserId, java.time.Instant.now());
        return after;
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
                                    Long operatorUserId, String idempotencyKey) {
        if (after.signum() < 0) {
            throw BusinessException.conflict("AGENT_SCORE_INSUFFICIENT", "您的账号剩余积分不足以完成本次上分！");
        }
        if (ledger.updateAgentScore(agentId, after) != 1) {
            throw BusinessException.notFound("MACHINE_NOT_FOUND", "机器账号不存在");
        }
        String direction = after.compareTo(before) > 0 ? "IN" : "OUT";
        BigDecimal amount = after.subtract(before).abs();
        ledger.insertEntry(agentId, null, entryType, direction, amount, before, after, reason,
                referenceType, referenceId, idempotencyKey, operatorUserId, java.time.Instant.now());
        return after;
    }

    private static BigDecimal scaled(BigDecimal value, String code, String message) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
        if (normalized.signum() < 0) {
            throw BusinessException.badRequest(code, message);
        }
        return normalized;
    }
}
