package com.xupan.server.system.service;

import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.agent.service.AgentConsoleService;
import com.xupan.server.agent.service.AgentLedgerService;
import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.chat.repository.PlayerPointRequestRepository;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 聊天室上下分申请（BY220 玩家在聊天室发「上100 / 下100」→ 后台审批）。
 *
 * <p>审批即"平台侧给玩家上下分"：按计划 §3.5「Q1=A1 的实现口径」与需求基线 §3.1，
 * 必须与 {@code PlayerDeskAdminService.grant()/adjust()} 同口径沿机器池流转——
 * {@code MACHINE} 范围扣/回操作者自己那台机器，{@code GLOBAL}/{@code GROUP} 扣/回**玩家归属机器**，
 * 托（BOT）不联动机器池。修复前这里直接 {@code walletService.grant/adjust}，是 A1 的漏网路径
 * （"凭空发分"）。</p>
 *
 * <p><b>下分申请严格对齐 BY220「创建即扣、拒绝退回」（2026-10-08 计划 §3.2）</b>：
 * 下分的钱在**申请创建时**就离开玩家余额（BY220 {@code application/common.php:1874-1876}，
 * 由 {@code ChatMessageService.processPointRequest} 调用
 * {@code VirtualWalletService.holdForPointRequest} 写入「下分预扣」流水）；</p>
 * <ul>
 *   <li>同意：**不再动玩家余额**，只给机器池加分（普才；托不联动）——
 *       BY220 {@code application/rbt/controller/Index.php:280-286}；</li>
 *   <li>拒绝：把预扣的积分退回玩家并写「下分退回」流水——
 *       BY220 {@code application/rbt/controller/Index.php:307-315}；</li>
 *   <li>重复审批/重复拒绝：申请单状态单向流转 + 流水幂等键双重兜底，**绝不重复记账**；</li>
 *   <li>改动前创建的 PENDING 下分申请没有预扣行，拒绝时**不退回**（否则凭空加钱），见
 *       {@link #refundDownRequestHold}。</li>
 * </ul>
 * <p>上分申请逐项保持改动前行为（创建不动余额、同意时普才扣池、池不足 409 且什么都不写）。</p>
 *
 * <p>合规：只处理虚拟积分，不涉及任何真实资金。</p>
 */
@Service
public class PlayerPointRequestService {

    private final PlayerPointRequestRepository repository;
    private final PlayerDeskScopeService scopeService;
    private final AgentConsoleService agentConsoleService;
    private final AgentRepository agentRepository;
    private final VirtualWalletService walletService;
    private final OperationAuditRepository auditRepository;

    public PlayerPointRequestService(PlayerPointRequestRepository repository,
                                     PlayerDeskScopeService scopeService,
                                     AgentConsoleService agentConsoleService,
                                     AgentRepository agentRepository,
                                     VirtualWalletService walletService,
                                     OperationAuditRepository auditRepository) {
        this.repository = repository;
        this.scopeService = scopeService;
        this.agentConsoleService = agentConsoleService;
        this.agentRepository = agentRepository;
        this.walletService = walletService;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public List<PlayerPointRequestRepository.Request> pending(long operatorUserId, int limit) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operatorUserId);
        return repository.findPending(limit, scope);
    }

    @Transactional
    public ReviewResult approve(long requestId, long operatorUserId, String reason) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operatorUserId);
        PlayerPointRequestRepository.Request request = requireRequest(requestId, scope);
        if (!"PENDING".equals(request.status())) {
            // 幂等：已审批的申请直接返回，钱包与机器池都不再变动（同一 requestId 不会重复记账）。
            return new ReviewResult(request, null);
        }
        String reviewReason = normalizeReason(reason, "聊天上下分申请批准");
        String idempotencyKey = "POINT-REQUEST:" + request.id();
        String direction = switch (request.requestType()) {
            case "TOP_UP" -> "TOP_UP";
            case "DOWN" -> "DOWN";
            default -> throw BusinessException.conflict("POINT_REQUEST_TYPE_INVALID", "积分申请类型无效");
        };
        // 计划 §3.5「Q1=A1 的实现口径」：审批就是"平台侧给玩家上下分"，必须扣/回机器池。
        //   MACHINE 范围 → 扣/回操作者自己那台机器（BY220 robot.php 口径）；
        //   GLOBAL/GROUP → 扣/回**玩家归属机器**（R1=做法①，操作者不选机器）。
        // 申请单里的 amount 是正数（聊天指令解析所得），内部按方向取符号。
        // 池不足时共用服务在写任何余额之前抛 409，事务回滚保证"钱包、机器池、申请单状态"三者一致。
        //
        // 2026-10-08 起 DOWN 走另一条入口（settleDownRequest）：玩家余额在申请创建时已预扣，
        // 同意只给机器池加分；TOP_UP 一行未改，仍是"钱包加分 + 普才扣机器池"。
        AgentConsoleService.ScoreChange change = "DOWN".equals(direction)
                ? settleDownRequest(request, scope, operatorUserId, idempotencyKey)
                : approveTopUp(request, scope, operatorUserId, idempotencyKey);
        if (!repository.markReviewed(request.id(), "APPROVED", operatorUserId, reviewReason, Instant.now())) {
            throw new IllegalStateException("积分申请审批状态更新失败");
        }
        auditRepository.record(operatorUserId, "USER_MANAGE", "POST",
                "/api/admin/player-desk/point-requests/" + request.id() + "/approve",
                Long.toString(request.id()), "SUCCESS", null,
                "requestType=" + request.requestType() + ",amount=" + request.amount()
                        + ",ledgerId=" + change.ledgerId() + ",machineScore=" + change.agentScore(), null,
                Instant.now());
        return new ReviewResult(requireRequest(request.id(), scope), change);
    }

    @Transactional
    public ReviewResult reject(long requestId, long operatorUserId, String reason) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operatorUserId);
        PlayerPointRequestRepository.Request request = requireRequest(requestId, scope);
        if (!"PENDING".equals(request.status())) {
            return new ReviewResult(request, null);
        }
        String reviewReason = normalizeReason(reason, "聊天上下分申请驳回");
        if (!repository.markReviewed(request.id(), "REJECTED", operatorUserId, reviewReason, Instant.now())) {
            throw new IllegalStateException("积分申请审批状态更新失败");
        }
        if ("DOWN".equals(request.requestType())) {
            refundDownRequestHold(request, operatorUserId);
        }
        auditRepository.record(operatorUserId, "USER_MANAGE", "POST",
                "/api/admin/player-desk/point-requests/" + request.id() + "/reject",
                Long.toString(request.id()), "SUCCESS", null,
                "requestType=" + request.requestType() + ",amount=" + request.amount(), null, Instant.now());
        return new ReviewResult(requireRequest(request.id(), scope), null);
    }

    /**
     * 上分申请审批：玩家钱包加分，且**普**才扣机器池（BY220 {@code rbt/controller/Index.php:268-279}）。
     * 池不足时由共用服务抛 409 且什么都不写。本方法与改动前逐项一致（回归重点）。
     */
    private AgentConsoleService.ScoreChange approveTopUp(PlayerPointRequestRepository.Request request,
                                                        PlayerDeskScopeService.Scope scope, long operatorUserId,
                                                        String idempotencyKey) {
        return scope instanceof PlayerDeskScopeService.Scope.Machine
                ? agentConsoleService.changeScore(operatorUserId, request.userId(),
                        "TOP_UP", request.amount(), idempotencyKey)
                : agentConsoleService.changeScoreForAgent(operatorUserId, ownerMachineId(request.userId()),
                        request.userId(), "TOP_UP", request.amount(), idempotencyKey);
    }

    /**
     * 下分申请审批：只做机器池结算，**玩家余额一分不动**。
     *
     * <p>BY220 {@code application/rbt/controller/Index.php:280-286}：同意下分时
     * {@code if ($res['isTuo'] == 0) robot.setInc('score', ...)}，随后 {@code afterScore} 记的就是
     * **当时的玩家余额**（没有 setInc/setDec）——因为钱已在 {@code common.php:1874-1876} 扣走。
     * 机器解析与上分同口径：{@code MACHINE} 用操作者自己那台，{@code GLOBAL}/{@code GROUP} 用玩家归属机器。</p>
     */
    private AgentConsoleService.ScoreChange settleDownRequest(PlayerPointRequestRepository.Request request,
                                                             PlayerDeskScopeService.Scope scope,
                                                             long operatorUserId, String idempotencyKey) {
        return scope instanceof PlayerDeskScopeService.Scope.Machine
                ? agentConsoleService.settleApprovedDownRequest(operatorUserId, request.userId(),
                        request.amount(), idempotencyKey)
                : agentConsoleService.settleApprovedDownRequestForAgent(operatorUserId,
                        ownerMachineId(request.userId()), request.userId(), request.amount(), idempotencyKey,
                        AgentLedgerService.INSUFFICIENT_OWNER_MACHINE_SCORE_MESSAGE);
    }

    /**
     * 下分申请被驳回 → 把创建时预扣的积分退回玩家（BY220
     * {@code application/rbt/controller/Index.php:307-315} 的 {@code setInc}）。
     *
     * <p><b>幂等（只退一次）</b>：① 申请单状态由 {@link PlayerPointRequestRepository#markReviewed}
     * 单向从 {@code PENDING} 改成 {@code REJECTED}（{@code WHERE status = 'PENDING'}），
     * 重复拒绝在 {@link #reject} 开头的"非 PENDING 直接返回"分支就结束；② 退回流水本身按申请编号
     * 使用固定幂等键 {@link PlayerPointRequestRepository#refundIdempotencyKey}，即便并发/重放
     * 走到这里，也只会命中既有流水重放（表级唯一键兜底），不会第二次加钱。</p>
     *
     * <p><b>无预扣行保护（关键）</b>：本改动上线前创建的 {@code PENDING} 下分申请是**旧口径**
     * ——创建时不扣玩家余额（所以没有 {@code POINT-REQUEST-HOLD:<id>} 预扣流水）。这类申请被拒绝时
     * 若照样退回，等于凭空给玩家加钱。因此这里**先确认预扣流水存在**才退回；不存在则只把申请单
     * 置为 REJECTED，余额与流水都不动。</p>
     */
    private void refundDownRequestHold(PlayerPointRequestRepository.Request request, long operatorUserId) {
        if (walletService.findLedgerByIdempotencyKey(
                PlayerPointRequestRepository.holdIdempotencyKey(request.id())).isEmpty()) {
            return;
        }
        walletService.refundForPointRequest(operatorUserId, request.userId(), request.amount(),
                "下分申请拒绝退回（聊天指令）：" + request.displayName(),
                PlayerPointRequestRepository.refundIdempotencyKey(request.id()));
    }

    /** 范围外的积分申请按“不存在”处理，避免通过 403 暴露跨机器数据存在性。 */
    private PlayerPointRequestRepository.Request requireRequest(long requestId, PlayerDeskScopeService.Scope scope) {
        return repository.findByIdForUpdate(requestId, scope)
                .orElseThrow(() -> BusinessException.notFound("POINT_REQUEST_NOT_FOUND", "积分申请不存在"));
    }

    /**
     * 平台侧审批要扣/回的机器：按**玩家归属**自动判定，与 {@code PlayerDeskAdminService.ownerMachineId}
     * 同口径同错误码（计划 §3.5【R1=做法①】，操作者不选机器）。
     *
     * <p>刻意在本类内保留一份同口径实现，而不是把另一个服务的私有方法提升为 public——
     * 避免两个服务之间为了一个三行判断产生跨服务耦合。玩家没有归属机器时**拒绝**（400），
     * 不退回"直接改钱包"的老路径：BY220 里分只能沿「子账号池/超管 → 机器 → 玩家」单向流转
     * （需求基线 §3.1、§3.6），退回老路径等于把"凭空发分"重新引回来。</p>
     */
    private long ownerMachineId(long userId) {
        return agentRepository.findCurrentAgentId(userId)
                .orElseThrow(() -> BusinessException.badRequest("PLAYER_DESK_MACHINE_REQUIRED",
                        "该玩家未归属任何机器，无法从机器积分池扣减"));
    }

    private static String normalizeReason(String reason, String fallback) {
        String value = reason == null || reason.isBlank() ? fallback : reason.trim();
        if (value.length() > 255) {
            throw BusinessException.badRequest("POINT_REQUEST_REASON_INVALID", "审批原因不能超过255个字符");
        }
        return value;
    }

    /**
     * 审批结果。
     *
     * @param scoreChange 机器池感知的上下分结果（{@code MACHINE} 扣操作者自己那台；
     *                    {@code GLOBAL}/{@code GROUP} 扣玩家归属机器；托不联动）。
     *                    重复审批（非 PENDING）返回 {@code null}。
     *                    **不进接口响应**：控制器只消费 {@code request()}（见
     *                    {@code PlayerDeskAdminController.approvePointRequest}）。
     */
    public record ReviewResult(PlayerPointRequestRepository.Request request,
                               AgentConsoleService.ScoreChange scoreChange) {
    }
}
