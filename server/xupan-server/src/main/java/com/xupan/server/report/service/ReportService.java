package com.xupan.server.report.service;

import com.xupan.server.auth.repository.OperationAuditRepository;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.chat.service.ChatMessageService;
import com.xupan.server.center.service.CenterUpstreamAccountService;
import com.xupan.server.game.service.VirtualWalletService;
import com.xupan.server.report.domain.ReportCommand;
import com.xupan.server.report.domain.ReportOutcome;
import com.xupan.server.report.repository.ReportFailureCancellationRepository;
import com.xupan.server.report.repository.ReportConfigRepository;
import com.xupan.server.report.repository.ReportUpstreamOrderRepository;
import com.xupan.server.system.service.PlayerDeskScopeService;
import com.xupan.server.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 代理后台（{@code /console}，BY220 {@code robot.php}）中心账号报网服务。
 *
 * <p>代理机器用分配的中心账号建立机器级登录会话；报单通过同进程内的中心服务完成，
 * 不发起外部 HTTP 请求。所有额度均为虚拟积分。</p>
 *
 * <p>范围规则：机器账号固定操作自己那台；平台管理员与子账号必须显式传 {@code machineId}
 * （未传 → 400 {@code PLAYER_DESK_MACHINE_REQUIRED}，不落到平台直属代理），
 * 且目标机器必须在自己范围内，范围外按"不存在"返回 404。</p>
 *
 * <p>权限规则：读走范围解析（{@code /api/admin/player-desk/**} 由 URL 层按
 * {@code USER_MANAGE | AGENT_PLAYER_MANAGE | AGENT_CONSOLE_READ} 放行，
 * {@code /api/agent/**} 只认 {@code AGENT_CONSOLE_READ}——V76 之后只有机器账号持有它），写操作要求
 * {@code USER_MANAGE}（平台管理员）或 {@code AGENT_PLAYER_MANAGE}（机器/子账号）。</p>
 *
 * <p>中心账号密码由超级管理员管理接口使用安全哈希保存；代理登录只返回状态和共享额度摘要，
 * 不返回密码哈希、登录令牌或其他机器的订单明细。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 审计动作码（BY220 报网配置）。 */
    public static final String AUDIT_ACTION = "AGENT_REPORT_CONFIG";

    /**
     * 上游上报订单状态机，与 V58 的 {@code ck_report_upstream_order_status} 保持一致。
     *
     * <p>对应需求文档《代理报网盘口与结算业务规则说明》§16.4：
     * 待处理 / 已接受 / 已拒绝 / 超时 / 已撤单 / 已退款 / 已结算。
     * 本阶段通过同进程中心服务写入 {@code PENDING/ACCEPTED/REJECTED/NOT_SENT}；
     * 后三个由中心上游第二层结算写入。</p>
     */
    public static final Set<String> REPORT_STATUSES = Set.of(
            "PENDING", "ACCEPTED", "REJECTED", "TIMEOUT", "CANCELLED", "REFUNDED", "SETTLED", "MANUAL", "NOT_SENT");
    /** 没有上报订单行时派生出来的状态：还没报过。 */
    public static final String STATUS_PENDING = "PENDING";
    /**
     * 允许重报的当前状态（对应 BY220 {@code reFeidan}：只重报未报/失败/超时）。
     *
     * <p>{@code MANUAL}（人工待处置）**刻意不在集合内**：它表示自动重报已达上限，
     * 继续放进来就会重新造成"失败单占满批次"的头部阻塞。人工重报走
     * {@link #resubmit} 显式入口。</p>
     */
    public static final Set<String> RESUBMITTABLE_STATUSES = Set.of("PENDING", "REJECTED", "TIMEOUT", "NOT_SENT");
    /** 尚未上报、可供自动上报链路处理的当前状态。 */
    public static final List<String> SUBMIT_FROM_STATUSES = List.of(STATUS_PENDING);
    /**
     * 自动上报链路的候选状态集合（含重报），供健康检查统计积压量使用。
     *
     * <p>刻意不含 {@code MANUAL}：它表示已转人工、不再参与自动重报，
     * 把它算进"积压"会让告警永远无法恢复。</p>
     */
    public static final List<String> RESUBMITTABLE_REPORT_STATUSES = List.of("PENDING", "REJECTED", "TIMEOUT", "NOT_SENT");
    private static final int MAX_PAGE_SIZE = 100;

    private final ReportConfigRepository repository;
    private final ReportUpstreamOrderRepository orderRepository;
    private final PlayerDeskScopeService scopeService;
    private final PermissionService permissionService;
    private final OperationAuditRepository auditRepository;
    private final CenterReportAdapter centerReportAdapter;
    private final CenterUpstreamAccountService centerAccountService;
    private final ReportFailureCancellationRepository cancellationRepository;
    private final VirtualWalletService walletService;
    private final ChatMessageService chatMessageService;
    /** Resolves the authenticated machine's account user for the center session lookup. */
    private final JdbcTemplate jdbc;
    /** 自动重报的次数上限；达到后转 MANUAL 人工处置（P0-2）。 */
    private final int retryMaxAttempts;
    /** 分级退避梯度；索引 = 第 N 次失败。为空表示不延迟（等同 BY220 每轮无条件重报）。 */
    private final List<Duration> retryBackoff;
    /** 每笔上报独立提交，避免单笔异常回滚整批订单。 */
    private final TransactionTemplate dispatchTransaction;

    public ReportService(ReportConfigRepository repository, ReportUpstreamOrderRepository orderRepository,
                         PlayerDeskScopeService scopeService,
                         PermissionService permissionService, OperationAuditRepository auditRepository,
                         CenterReportAdapter centerReportAdapter,
                         CenterUpstreamAccountService centerAccountService,
                         ReportFailureCancellationRepository cancellationRepository, VirtualWalletService walletService,
                         ChatMessageService chatMessageService,
                         JdbcTemplate jdbc,
                         PlatformTransactionManager transactionManager,
                         @Value("${xupan.report.retry.max-attempts:5}") int retryMaxAttempts,
                         @Value("${xupan.report.retry.backoff:30s,2m,10m,1h}") String retryBackoffSpec) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.scopeService = scopeService;
        this.permissionService = permissionService;
        this.auditRepository = auditRepository;
        this.centerReportAdapter = centerReportAdapter;
        this.centerAccountService = centerAccountService;
        this.cancellationRepository = cancellationRepository;
        this.walletService = walletService;
        this.chatMessageService = chatMessageService;
        this.jdbc = jdbc;
        this.dispatchTransaction = new TransactionTemplate(transactionManager);
        this.dispatchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBackoff = parseBackoff(retryBackoffSpec);
    }

    /** 解析退避梯度，例如 {@code "30s,2m,10m,1h"}。非法项跳过，不影响启动。 */
    private static List<Duration> parseBackoff(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        List<Duration> parsed = new ArrayList<>();
        for (String part : spec.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            Duration duration = parseDuration(token);
            if (duration != null) {
                parsed.add(duration);
            }
        }
        return List.copyOf(parsed);
    }

    /** 支持 {@code 30s} / {@code 2m} / {@code 1h} / {@code 500ms} 四种简写，也接受纯秒数。 */
    private static Duration parseDuration(String token) {
        String lower = token.toLowerCase();
        try {
            if (lower.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(lower.substring(0, lower.length() - 2)));
            }
            char unit = lower.charAt(lower.length() - 1);
            if (unit == 's' || unit == 'm' || unit == 'h') {
                long value = Long.parseLong(lower.substring(0, lower.length() - 1));
                return switch (unit) {
                    case 's' -> Duration.ofSeconds(value);
                    case 'm' -> Duration.ofMinutes(value);
                    default -> Duration.ofHours(value);
                };
            }
            return Duration.ofSeconds(Long.parseLong(lower));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 读取机器自动上报开关、中心会话状态和上报统计。 */
    @Transactional(readOnly = true)
    public ReportConfigView config(long operator, Long machineId) {
        long targetAgentId = targetAgentId(operator, machineId);
        return view(targetAgentId);
    }

    /**
     * 保存机器报网配置。
     *
     * <p>{@code null} 表示对应开关保持原值；审计只记录机器和开关状态。</p>
     */
    @Transactional
    public ReportConfigView saveConfig(long operator, Long machineId, ReportConfigUpdate update) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        ReportConfigRepository.ReportConfigRow current = requireConfig(targetAgentId);
        boolean auto = update.reportAuto() == null ? current.reportAuto() : update.reportAuto();
        boolean withdraw = update.reportWithdraw() == null ? current.reportWithdraw() : update.reportWithdraw();
        repository.updateControlConfig(targetAgentId, auto, withdraw);
        audit(operator, "PUT", "/api/admin/player-desk/report", targetAgentId,
                "auto=" + auto + ",withdraw=" + withdraw);
        return view(targetAgentId);
    }

    /** Logs a center account in for the authenticated machine; machine identity is never request supplied. */
    @Transactional
    public ReportConfigView login(long authenticatedUserId, String username, String password, String ip) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(authenticatedUserId);
        if (!(scope instanceof PlayerDeskScopeService.Scope.Machine)) {
            throw BusinessException.forbidden("CENTER_MACHINE_LOGIN_REQUIRED", "请使用机器账号登录中心账号");
        }
        CenterUpstreamAccountService.LoginView result = centerAccountService.login(
                authenticatedUserId, username, password, ip);
        long targetAgentId = result.machineAgentId();
        audit(authenticatedUserId, "POST", "/api/admin/player-desk/report/login", targetAgentId,
                "centerAccountId=" + result.account().accountId());
        return view(targetAgentId);
    }

    /** 结束当前机器的中心账号会话；机器自动上报和失败退单开关保持不变。 */
    @Transactional
    public ReportConfigView logout(long authenticatedUserId, String ip) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(authenticatedUserId);
        if (!(scope instanceof PlayerDeskScopeService.Scope.Machine machine)) {
            throw BusinessException.forbidden("CENTER_MACHINE_LOGIN_REQUIRED", "请使用机器账号退出中心账号");
        }
        centerAccountService.logout(authenticatedUserId, ip);
        audit(authenticatedUserId, "POST", "/api/admin/player-desk/report/logout", machine.agentId(),
                "centerSessionRevoked=true");
        return view(machine.agentId());
    }

    /** 按派生上报状态（可空=全部）列出本机器范围内的注单。 */
    @Transactional(readOnly = true)
    public ReportOrderPage orders(long operator, Long machineId, String status, int page, int pageSize) {
        long targetAgentId = targetAgentId(operator, machineId);
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.badRequest("REPORT_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        String normalizedStatus = normalizeStatus(status);
        List<ReportUpstreamOrderRepository.OrderRow> items =
                orderRepository.findOrders(targetAgentId, normalizedStatus, page, pageSize);
        return new ReportOrderPage(items, page, pageSize,
                orderRepository.countOrders(targetAgentId, normalizedStatus));
    }

    /**
     * Records the agent's one-time local-bet conclusion after the center has canceled or refunded its order.
     * KEEP_LOCAL leaves the player's bet and wallet untouched; REFUND_CANCEL_LOCAL atomically cancels the bet
     * and returns its stake using a dedicated ledger idempotency key.
     */
    @Transactional
    public LocalDispositionResult disposeLocalBet(long operator, Long machineId, long betId,
                                                  String requestedDecision, String requestedReason) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        String decision = normalizeLocalDecision(requestedDecision);
        String reason = requestedReason == null ? "" : requestedReason.trim();
        if (reason.isEmpty()) {
            throw BusinessException.badRequest("REPORT_LOCAL_DISPOSITION_REASON_REQUIRED", "处理原因不能为空");
        }
        if (reason.length() > 255) {
            throw BusinessException.badRequest("REPORT_LOCAL_DISPOSITION_REASON_TOO_LONG", "处理原因不能超过 255 个字符");
        }

        ReportUpstreamOrderRepository.LocalDispositionBet bet = orderRepository
                .findDispositionBetForUpdate(betId, targetAgentId)
                .orElseThrow(() -> BusinessException.notFound("REPORT_BET_NOT_FOUND", "注单不存在或不在当前机器范围内"));
        ReportUpstreamOrderRepository.LocalDispositionRow existing = orderRepository.findLocalDisposition(betId)
                .orElse(null);
        if (existing != null) {
            if (!existing.decision().equals(decision) || !existing.reason().equals(reason)) {
                throw BusinessException.conflict("REPORT_LOCAL_DISPOSITION_ALREADY_DECIDED",
                        "该注单已作出不同的本地处理结论，不能修改");
            }
            return new LocalDispositionResult(betId, existing.centerOutcome(), existing.decision(),
                    existing.reason(), bet.settlementStatus(),
                    "REFUND_CANCEL_LOCAL".equals(existing.decision()) ? bet.stake() : BigDecimal.ZERO,
                    existing.operatorUserId(), existing.createdAt(), true);
        }

        if (!"PENDING".equals(bet.settlementStatus()) || bet.settledAt() != null) {
            throw BusinessException.conflict("REPORT_LOCAL_DISPOSITION_BET_NOT_PENDING",
                    "玩家本地注单已结算或已取消，不能再处理");
        }
        ReportUpstreamOrderRepository.AttemptRow attempt = orderRepository.findLatestAttemptForUpdate(betId)
                .orElse(null);
        if (attempt == null || !"CENTER".equals(attempt.reportType()) || !"MANUAL".equals(attempt.status())
                || attempt.centerAgentId() == null
                || (!"CENTER_CANCELLED".equals(attempt.responseCode())
                && !"CENTER_REFUNDED".equals(attempt.responseCode()))) {
            throw BusinessException.conflict("REPORT_LOCAL_DISPOSITION_CENTER_RESULT_REQUIRED",
                    "仅中心明确撤单或退款、等待本地核对的注单可以处理");
        }

        String centerOutcome = "CENTER_CANCELLED".equals(attempt.responseCode()) ? "CANCELLED" : "REFUNDED";
        Instant decidedAt = Instant.now();
        BigDecimal refundedAmount = BigDecimal.ZERO;
        String localSettlementStatus = bet.settlementStatus();
        if ("REFUND_CANCEL_LOCAL".equals(decision)) {
            if (!cancellationRepository.cancelPendingBetForCenterDisposition(betId, decidedAt)) {
                throw BusinessException.conflict("REPORT_LOCAL_DISPOSITION_BET_NOT_PENDING",
                        "玩家本地注单已结算或已取消，不能退款");
            }
            walletService.refundForCenterDisposition(operator, bet.sysUserId(), betId, bet.accountId(),
                    bet.issueNumber(), bet.stake(), reason);
            refundedAmount = bet.stake();
            localSettlementStatus = "CANCELED";
        }

        if (orderRepository.resolveManualCenterAttempt(attempt.id(), centerOutcome, attempt.responseCode(),
                decidedAt) != 1) {
            throw BusinessException.conflict("REPORT_LOCAL_DISPOSITION_ATTEMPT_CHANGED",
                    "中心上报状态已变化，请刷新后重新核对");
        }
        orderRepository.insertLocalDisposition(betId, attempt.id(), targetAgentId, attempt.centerAgentId(),
                centerOutcome, decision, reason, operator, decidedAt);
        auditRepository.record(operator, AUDIT_ACTION, "POST",
                "/api/admin/player-desk/report/orders/" + betId + "/local-disposition",
                Long.toString(betId), "SUCCESS", null,
                "machineId=" + targetAgentId + ",centerOutcome=" + centerOutcome + ",decision=" + decision
                        + ",stake=" + bet.stake() + ",refunded=" + refundedAmount + ",reason=" + reason,
                null, decidedAt);
        return new LocalDispositionResult(betId, centerOutcome, decision, reason, localSettlementStatus,
                refundedAmount, operator, decidedAt, false);
    }

    private static String normalizeLocalDecision(String requestedDecision) {
        String decision = requestedDecision == null ? "" : requestedDecision.trim().toUpperCase(Locale.ROOT);
        if (!"KEEP_LOCAL".equals(decision) && !"REFUND_CANCEL_LOCAL".equals(decision)) {
            throw BusinessException.badRequest("REPORT_LOCAL_DISPOSITION_DECISION_INVALID", "本地处理结论无效");
        }
        return decision;
    }

    /**
     * 重报单笔注单（对应 BY220 {@code reFeidan}）。
     *
     * <p>只允许派生状态为 {@code PENDING/REJECTED/TIMEOUT} 且属于该机器的注单；
     * 范围外的注单按"不存在"返回 404，状态不允许返回 409。
     * 适配器结果与状态回写在**同一事务**内完成，并写审计（不含任何凭据）。</p>
     */
    @Transactional
    public ResubmitResult resubmit(long operator, Long machineId, long betId) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        return doResubmit(operator, targetAgentId, betId, false);
    }

    /**
     * 人工重报（P0-2 附带能力）：把已达自动重试上限、转入 {@code MANUAL} 的注单重新交给上报链路。
     *
     * <p>为什么必须有这个入口：{@code MANUAL} 被刻意排除在自动候选集合之外（否则失败单会重新
     * 堵死批次），因此如果不提供显式重报，人工处置后这些单就永远报不出去了。
     * 重报会**清零尝试计数并解除人工标记**，让退避从第一档重新开始。</p>
     */
    @Transactional
    public ResubmitResult resubmitManual(long operator, Long machineId, long betId) {
        long targetAgentId = targetAgentIdForWrite(operator, machineId);
        return doResubmit(operator, targetAgentId, betId, true);
    }

    private ResubmitResult doResubmit(long operator, long targetAgentId, long betId, boolean allowManual) {
        ReportUpstreamOrderRepository.BetRow bet = orderRepository.findBetForAgentForUpdate(betId, targetAgentId)
                .orElseThrow(() -> BusinessException.notFound("REPORT_BET_NOT_FOUND", "注单不存在或不在当前机器范围内"));
        if ("RECOVERY_CENTER_ACCOUNT_UNKNOWN".equals(bet.responseCode())) {
            throw BusinessException.conflict("REPORT_RECONCILIATION_REQUIRED",
                    "原中心账号归属无法确认，需先完成人工核对，不能直接换账号重报");
        }
        if ("CENTER_CANCELLED".equals(bet.responseCode()) || "CENTER_REFUNDED".equals(bet.responseCode())) {
            throw BusinessException.conflict("REPORT_CENTER_DISPOSITION_REQUIRES_RECONCILIATION",
                    "中心订单已撤销或退款，需先核对本地注单，不能直接重报");
        }
        boolean manual = "MANUAL".equals(bet.reportStatus());
        if (manual && !allowManual) {
            throw BusinessException.conflict("REPORT_BET_STATUS_INVALID",
                    "该注单已转人工待处置，请走人工重报入口");
        }
        if (!manual && !RESUBMITTABLE_STATUSES.contains(bet.reportStatus())) {
            throw BusinessException.conflict("REPORT_BET_STATUS_INVALID", "该注单当前上报状态不允许重报");
        }
        if (!orderRepository.isReportableBet(betId, targetAgentId)) {
            throw BusinessException.conflict("REPORT_BET_NOT_REPORTABLE", "期号已封盘或注单已取消/结算，不能报送");
        }
        if (manual) {
            // 解除人工标记并清零退避，让重试梯度重新开始。
            orderRepository.resetForManualResubmit(betId);
        }
        ReportConfigRepository.ReportConfigRow config = requireConfig(targetAgentId);
        if (!config.reportOnline()) {
            throw BusinessException.conflict("CENTER_SESSION_REQUIRED", "请先登录有效中心账号后再重报");
        }
        String newStatus = submitAttempt(bet, config);
        audit(operator, "POST", "/api/admin/player-desk/report/orders/" + betId
                        + (manual ? "/manual-resubmit" : "/resubmit"), targetAgentId,
                "betId=" + betId + ",reportStatus=" + newStatus + ",fromManual=" + manual);
        return new ResubmitResult(betId, newStatus, view(targetAgentId));
    }

    /**
     * 供上报链路调用的自动上报入口（BY220 飞单）。
     *
     * <p>仅当注单尚未报送、机器自动上报开启且当前中心账号机器会话有效时才提交；
     * 其余情况直接跳过（返回 {@link Optional#empty()}），不影响玩家本地下注。</p>
     *
     * <p>每次提交都会在 {@code report_upstream_order} **追加一行**上报尝试（不覆盖历史），
     * 随后在同一事务内落结果。</p>
     *
     * @return 本次提交后的上报状态；未提交时为 {@link Optional#empty()}
     */
    @Transactional
    public Optional<String> submitForBet(long betId) {
        if (betId <= 0) {
            return Optional.empty();
        }
        ReportUpstreamOrderRepository.BetRow bet = orderRepository.findBet(betId).orElse(null);
        if (bet == null || !SUBMIT_FROM_STATUSES.contains(bet.reportStatus())) {
            return Optional.empty();
        }
        if (!orderRepository.isReportableBet(betId, bet.agentId())) {
            return Optional.empty();
        }
        ReportConfigRepository.ReportConfigRow config = repository.findConfig(bet.agentId()).orElse(null);
        if (config == null || !config.reportAuto() || !config.reportOnline()) {
            return Optional.empty();
        }
        return Optional.of(submitAttempt(bet, config));
    }

    /**
     * 追加一次上报尝试并立即落结果（同一事务）。
     *
     * <p>顺序：写 {@code PENDING} 尝试行（留痕、幂等键 {@code REPORT-<betId>-<attemptNo>}）
     * → 同进程调用中心接单服务 → 把结果回写到该尝试行。明确拒绝且失败退单开启时执行退单；
     * 未发送或待核实状态不触发退款。</p>
     *
     * @return 本次尝试的结果状态；退单成功时为 {@code WITHDRAWN}
     */
    private String submitAttempt(ReportUpstreamOrderRepository.BetRow bet,
                                 ReportConfigRepository.ReportConfigRow config) {
        ReportUpstreamOrderRepository.AttemptRow latest = orderRepository.findLatestAttempt(bet.betId()).orElse(null);
        if (isRecoverableCenterAttempt(latest)) {
            return recoverPendingAttempt(bet, latest);
        }
        Instant sentAt = Instant.now();
        int attemptNo = orderRepository.nextAttemptNo(bet.betId());
        long orderId = orderRepository.insertAttempt(bet.betId(), bet.agentId(), config.centerAgentId(),
                attemptNo, STATUS_PENDING,
                CenterReportAdapter.CODE, null, null,
                "REPORT-" + bet.betId() + "-" + attemptNo, requestSummary(bet), null, null, sentAt, null);

        ReportCommand command = new ReportCommand(bet.betId(), bet.accountId(), bet.agentId(), bet.issueNumber(),
                bet.playType(), bet.parametersText(), bet.stake(), bet.odds(), CenterReportAdapter.CODE,
                null, null, true);
        ReportOutcome outcome = centerReportAdapter.submit(command);
        String newStatus = mapOutcome(outcome);
        if ("PENDING".equals(newStatus) || "TIMEOUT".equals(newStatus)) {
            orderRepository.deferPendingAttempt(orderId, 0, newStatus, outcome.name(),
                    responseMessage(outcome), Instant.now().plus(retryBackoff(1)));
            return newStatus;
        }
        if (orderRepository.resolveAttempt(orderId, 0, newStatus, outcome.name(), responseMessage(outcome),
                upstreamOrderNo(bet), Instant.now()) == 0) {
            return latestAttemptStatus(bet.betId(), newStatus);
        }

        if ("REJECTED".equals(newStatus) && withdrawOnFailure(bet, orderId, config)) {
            // 明确拒绝且已开启失败退单：原单保留并记为已退款
            return "WITHDRAWN";
        }
        if (!"REJECTED".equals(newStatus) && !"NOT_SENT".equals(newStatus)) {
            return newStatus;
        }
        // 失败或超时：安排下一次重报时间点，并检查是否已达重试上限。
        scheduleRetryOrManual(bet.betId(), orderId, newStatus);
        return newStatus;
    }

    private String recoverPendingAttempt(ReportUpstreamOrderRepository.BetRow bet,
                                         ReportUpstreamOrderRepository.AttemptRow attempt) {
        if (attempt.centerAgentId() == null) {
            int updated = orderRepository.resolveAttempt(attempt.id(), attempt.attemptCount(), "MANUAL",
                    "RECOVERY_CENTER_ACCOUNT_UNKNOWN",
                    "原中心账号归属无法确认，需先完成人工核对", attempt.upstreamOrderNo(), Instant.now());
            if (updated == 0) {
                return latestAttemptStatus(bet.betId(), "MANUAL");
            }
            auditRepository.record(0L, AUDIT_ACTION, "PATCH", "/internal/report/recovery",
                    Long.toString(bet.betId()), "MANUAL", "RECOVERY_CENTER_ACCOUNT_UNKNOWN",
                    "attemptId=" + attempt.id() + ",refund=false,newCenterOrder=false", null, Instant.now());
            return "MANUAL";
        }

        ReportCommand command = new ReportCommand(bet.betId(), bet.accountId(), bet.agentId(), bet.issueNumber(),
                bet.playType(), bet.parametersText(), bet.stake(), bet.odds(), CenterReportAdapter.CODE,
                null, null, true);
        ReportOutcome outcome = centerReportAdapter.recover(command, attempt.centerAgentId());

        String status = mapOutcome(outcome);
        if ("PENDING".equals(status) || "TIMEOUT".equals(status)) {
            return deferRecovery(bet.betId(), attempt, outcome.name(), responseMessage(outcome), status);
        }
        if (orderRepository.resolveAttempt(attempt.id(), attempt.attemptCount(), status, outcome.name(),
                responseMessage(outcome), upstreamOrderNo(bet), Instant.now()) == 0) {
            return latestAttemptStatus(bet.betId(), status);
        }
        if ("REJECTED".equals(status)) {
            ReportConfigRepository.ReportConfigRow config = repository.findConfig(bet.agentId()).orElse(null);
            if (config != null && withdrawOnFailure(bet, attempt.id(), config)) {
                return "WITHDRAWN";
            }
            scheduleRetryOrManual(bet.betId(), attempt.id(), status);
        } else if ("NOT_SENT".equals(status)) {
            scheduleRetryOrManual(bet.betId(), attempt.id(), status);
        }
        return status;
    }

    private String deferRecovery(long betId, ReportUpstreamOrderRepository.AttemptRow attempt,
                                 String responseCode, String responseMessage, String status) {
        Duration delay = retryBackoff(Math.max(1, attempt.attemptCount() + 1));
        int updated = orderRepository.deferPendingAttempt(attempt.id(), attempt.attemptCount(), status,
                responseCode, responseMessage, Instant.now().plus(delay));
        return updated == 0 ? latestAttemptStatus(betId, status) : status;
    }

    private static boolean isRecoverableCenterAttempt(ReportUpstreamOrderRepository.AttemptRow attempt) {
        return attempt != null && CenterReportAdapter.CODE.equals(attempt.reportType())
                && ("PENDING".equals(attempt.status()) || "TIMEOUT".equals(attempt.status()));
    }

    /**
     * 安排重报退避；超过上限转人工待处置（P0-2）。
     *
     * <p>没有这一步，失败单会每 2.4 秒无条件重报，并且永远留在候选集合里——
     * 这正是"累积 50 笔坏单就堵死整条上报链路"的根因。</p>
     *
     * <p>退避梯度默认 30s → 2min → 10min → 1h，第 5 次起转 {@code MANUAL}。
     * 全部可用配置覆盖；把 {@code report.retry.max-attempts} 设为很大的值、
     * 退避梯度全设 0，即可退回 BY220 的"每轮无条件重报"行为。</p>
     */
    private void scheduleRetryOrManual(long betId, long attemptId, String newStatus) {
        int attempts = orderRepository.scheduleRetry(attemptId, null);
        if (attempts < 0) {
            return;
        }
        int maxAttempts = retryMaxAttempts;
        if (attempts >= maxAttempts) {
            if (orderRepository.markManual(attemptId, "RETRY_EXHAUSTED",
                    "自动重报已达上限 " + maxAttempts + " 次，转人工处置") == 0) {
                return;
            }
            log.warn("上报重试达上限，转人工处置 betId={} attempts={} lastStatus={}",
                    betId, attempts, newStatus);
            auditRepository.record(0L, AUDIT_ACTION, "PATCH", "/api/admin/player-desk/report/manual",
                    Long.toString(betId), "SUCCESS", null,
                    "reason=RETRY_EXHAUSTED,attempts=" + attempts + ",lastStatus=" + newStatus, null,
                    Instant.now());
            return;
        }
        orderRepository.updateRetryAt(attemptId, Instant.now().plus(retryBackoff(attempts)));
    }

    private String latestAttemptStatus(long betId, String fallback) {
        return orderRepository.findLatestAttempt(betId).map(ReportUpstreamOrderRepository.AttemptRow::status)
                .orElse(fallback);
    }

    /** 第 {@code attempts} 次失败后的退避时长（越界取最后一档）。 */
    private Duration retryBackoff(int attempts) {
        if (retryBackoff.isEmpty()) {
            return Duration.ZERO;
        }
        int index = Math.min(Math.max(attempts, 1), retryBackoff.size()) - 1;
        return retryBackoff.get(index);
    }

    /** 上报请求摘要（落库留痕；不含任何凭据）。 */
    private static String requestSummary(ReportUpstreamOrderRepository.BetRow bet) {
        return "bet=" + bet.betId() + ",issue=" + bet.issueNumber() + ",play=" + bet.playType()
                + ",stake=" + bet.stake();
    }

    /**
     * 上游订单号。
     *
     * <p>中心侧使用本地注单 ID 作为稳定幂等号；每次上报尝试都关联同一个中心订单号。</p>
     */
    private static String upstreamOrderNo(ReportUpstreamOrderRepository.BetRow bet) {
        return "BET-" + bet.betId();
    }

    private static String responseMessage(ReportOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> "本系统中心已接受";
            case REJECTED -> "本系统中心明确拒绝";
            case PENDING -> "中心订单仍处理中，继续核实原幂等单";
            case TIMEOUT -> "中心订单结果待核实";
            case NOT_SENT -> "中心账号会话已失效，订单未发送";
            case CENTER_CANCELLED -> "中心订单已撤销，本地注单需人工核对";
            case CENTER_REFUNDED -> "中心订单已退款，本地注单需人工核对";
        };
    }

    /**
     * 批量上报（BY220 {@code Websocket.php:658-673} 的飞单轮询）。
     *
     * <p>BY220 每 2.4 秒捞出"未报/失败"的订单重新飞单；xupan 由 {@code ReportDispatchScheduler}
     * 以可配置间隔调用本方法，只处理已开启自动上报、在线且报网类型已配置的机器。</p>
     *
     * @param includeRetry true 时一并重报 {@code REJECTED/TIMEOUT}（对应 BY220 轮询捞 {@code flyers_status=3}）
     * @return 处理条数与退单条数
     */
    public DispatchResult dispatchPending(boolean includeRetry, int limit) {
        if (limit <= 0) {
            return new DispatchResult(0, 0, 0);
        }
        List<ReportUpstreamOrderRepository.AttemptRow> pendingAttempts =
                orderRepository.findPendingAttempts(limit);
        int scanned = 0;
        int submitted = 0;
        int withdrawn = 0;
        for (ReportUpstreamOrderRepository.AttemptRow attempt : pendingAttempts) {
            scanned++;
            try {
                String result = dispatchTransaction.execute(status -> {
                    ReportUpstreamOrderRepository.BetRow bet =
                            orderRepository.findBet(attempt.betId()).orElse(null);
                    return bet == null ? null : recoverPendingAttempt(bet, attempt);
                });
                if (result != null) {
                    submitted++;
                    if ("WITHDRAWN".equals(result)) {
                        withdrawn++;
                    }
                }
            } catch (RuntimeException exception) {
                log.error("报网未决订单恢复失败 attemptId={} betId={}", attempt.id(), attempt.betId(), exception);
            }
        }

        List<String> statuses = includeRetry
                ? RESUBMITTABLE_REPORT_STATUSES
                : SUBMIT_FROM_STATUSES;
        List<ReportUpstreamOrderRepository.BetRow> bets = orderRepository.findSubmittableBets(statuses, limit);
        for (ReportUpstreamOrderRepository.BetRow bet : bets) {
            scanned++;
            try {
                Optional<String> result = dispatchTransaction.execute(status -> submitForBet(bet.betId()));
                if (result == null || result.isEmpty()) {
                    continue;
                }
                submitted++;
                if ("WITHDRAWN".equals(result.get())) {
                    withdrawn++;
                }
            } catch (RuntimeException exception) {
                log.error("报网上报注单失败 betId={}", bet.betId(), exception);
            }
        }
        return new DispatchResult(scanned, submitted, withdrawn);
    }

    /** 一次上报轮询的处理结果。 */
    public record DispatchResult(int scanned, int submitted, int withdrawn) {
    }

    /**
     * 失败自动退单（BY220 {@code common.php:1627-1641}）。
     *
     * <p>BY220 的时序：{@code $make == 3}（上报失败）且机器开启 {@code flyers_withdraw} 时，
     * 先给玩家 {@code setInc('score', $order['score'])}（退还本金）、发一条"注单取消"消息，
     * 最后 {@code db('record')->delete($id)} 物理删除注单；未开启时只把 {@code flyers_status} 标成 3。</p>
     *
     * <p>按中心上游闭环基线保留原注单：同一事务内将订单改为 {@code CANCELED}、写幂等退款
     * （幂等键 {@code CANCEL_REFUND:<betId>}）、保留上报尝试并推进为 {@code REFUNDED}，再写审计。
     * {@code TIMEOUT} 一律只标记不退款
     * （BY220 的超时状态未知，不能当失败处理）。</p>
     *
     * @return true 表示已退单（原注单和上报尝试均保留）
     */
    private boolean withdrawOnFailure(ReportUpstreamOrderRepository.BetRow bet, long attemptId,
                                      ReportConfigRepository.ReportConfigRow config) {
        if (!config.reportWithdraw()) {
            return false;
        }
        Instant refundedAt = Instant.now();
        if (!cancellationRepository.cancelPendingBet(bet.betId(), refundedAt)) {
            // 注单已结算/取消或已被其它路径处理：不重复退款
            return false;
        }
        walletService.refundForCancellation(bet.sysUserId(), bet.betId(), bet.accountId(),
                bet.issueNumber(), bet.stake());
        cancellationRepository.markAttemptRefunded(attemptId, refundedAt);
        // BY220 失败退单会发一条"注单取消"房间提示（common.php:1635 addMsg）。
        // 反馈失败不能影响退款结果，因此单独兜住异常；幂等键保证重复处理不重复发消息。
        try {
            chatMessageService.publishRobotNotice("main", bet.issueNumber(),
                    "CHAT-REPORT-WITHDRAW-" + bet.betId(),
                    "@" + bet.betId() + " 注单取消（上报失败自动退单）", bet.agentId(), Instant.now());
        } catch (RuntimeException feedbackFailure) {
            log.warn("失败退单的聊天室取消反馈写入失败 betId={} {}", bet.betId(),
                    feedbackFailure.getMessage());
        }
        auditRepository.record(null, AUDIT_ACTION, "POST", "/api/admin/player-desk/report/withdraw",
                Long.toString(bet.betId()), "SUCCESS", null,
                "machineId=" + bet.agentId() + ",reason=REPORT_FAILED,refunded=" + bet.stake()
                        + ",betPreserved=true", null, refundedAt);
        return true;
    }

    private static String mapOutcome(ReportOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> "ACCEPTED";
            case REJECTED -> "REJECTED";
            case PENDING -> "PENDING";
            case TIMEOUT -> "TIMEOUT";
            case NOT_SENT -> "NOT_SENT";
            case CENTER_CANCELLED, CENTER_REFUNDED -> "MANUAL";
        };
    }

    private ReportConfigView view(long agentId) {
        ReportConfigRepository.ReportConfigRow config = requireConfig(agentId);
        ReportUpstreamOrderRepository.StatusCounts counts = orderRepository.counts(agentId);
        CenterUpstreamAccountService.AccountView account = centerAccountForMachine(agentId).orElse(null);
        return new ReportConfigView(config.agentId(), config.reportAuto(), account != null && account.online(),
                config.reportWithdraw(), account == null ? null : account.username(),
                account == null ? null : account.expiresAt(),
                account == null ? BigDecimal.ZERO.setScale(2) : account.availableBalance(),
                account == null ? BigDecimal.ZERO.setScale(2) : account.frozenBalance(),
                account == null ? BigDecimal.ZERO.setScale(2) : account.openStake(),
                counts.timeoutCount(), counts.unreportedCount(), counts.failedCount());
    }

    private Optional<CenterUpstreamAccountService.AccountView> centerAccountForMachine(long machineAgentId) {
        Long machineUserId = jdbc.query("""
                SELECT account_user_id FROM agent
                 WHERE id = ? AND deleted_at IS NULL AND system_owned = FALSE
                """, rs -> rs.next() ? rs.getLong("account_user_id") : null, machineAgentId);
        return machineUserId == null ? Optional.empty() : centerAccountService.currentForMachine(machineUserId);
    }

    private ReportConfigRepository.ReportConfigRow requireConfig(long agentId) {
        return repository.findConfig(agentId)
                .orElseThrow(() -> BusinessException.notFound("MACHINE_NOT_FOUND", "机器不存在或已停用"));
    }

    /** 读范围：机器账号固定自己；其余身份必须显式传机器且机器在范围内。 */
    private long targetAgentId(long operator, Long machineId) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        return targetAgentId(scope, machineId);
    }

    /** 写范围：在读范围之上追加写权限（USER_MANAGE 或 AGENT_PLAYER_MANAGE）。 */
    private long targetAgentIdForWrite(long operator, Long machineId) {
        PlayerDeskScopeService.Scope scope = scopeService.requireAccess(operator);
        if (!scope.global() && !permissionService.hasPermission(operator, "AGENT_PLAYER_MANAGE")) {
            throw BusinessException.forbidden("REPORT_CONFIG_FORBIDDEN", "没有报网配置权限");
        }
        return targetAgentId(scope, machineId);
    }

    /**
     * 报网场景的机器解析：非机器身份必须显式选机器。
     *
     * <p>与创建玩家不同，报网配置**不允许**把"未传机器"落到平台直属代理，因此这里先拦
     * {@code machineId == null} 再交给 {@link PlayerDeskScopeService#requireTargetAgentId}；
     * 机器账号传了别人的机器按"不存在"返回 404，不做静默改写。</p>
     */
    private long targetAgentId(PlayerDeskScopeService.Scope scope, Long machineId) {
        if (scope instanceof PlayerDeskScopeService.Scope.Machine machine) {
            if (machineId != null && machineId != machine.agentId()) {
                throw BusinessException.notFound("MACHINE_NOT_FOUND", "机器不存在或不在当前范围内");
            }
            return machine.agentId();
        }
        if (machineId == null) {
            throw BusinessException.badRequest(PlayerDeskScopeService.MACHINE_REQUIRED_CODE,
                    "报网操作必须指定机器");
        }
        return scopeService.requireTargetAgentId(scope, machineId);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!REPORT_STATUSES.contains(normalized)) {
            throw BusinessException.badRequest("REPORT_STATUS_INVALID", "上报状态无效");
        }
        return normalized;
    }

    private void audit(long operator, String method, String path, long machineId, String summary) {
        auditRepository.record(operator, AUDIT_ACTION, method, path, Long.toString(machineId), "SUCCESS",
                null, "machineId=" + machineId + "," + summary, null, Instant.now());
    }

    /** Report settings exposed to the machine-scoped console surface. */
    public record ReportConfigView(long machineId, boolean reportAuto, boolean reportOnline,
                                   boolean reportWithdraw, String centerAccountUsername,
                                   Instant centerAccountExpiresAt, BigDecimal centerAvailableBalance,
                                   BigDecimal centerFrozenBalance, BigDecimal centerOpenStake,
                                   long timeoutOrderCount, long unreportedCount, long failedOrderCount) {
    }

    /** 报网开关更新；{@code null} 表示保持原值。 */
    public record ReportConfigUpdate(Boolean reportAuto, Boolean reportWithdraw) {
    }

    /** 报网订单分页结果。 */
    public record ReportOrderPage(List<ReportUpstreamOrderRepository.OrderRow> items, int page, int pageSize, long total) {
    }

    /** 重报结果；带最新配置快照便于前端一次刷新。 */
    public record ResubmitResult(long betId, String reportStatus, ReportConfigView config) {
    }

    public record LocalDispositionResult(long betId, String centerOutcome, String decision, String reason,
                                         String settlementStatus, BigDecimal refundedAmount,
                                         long operatorUserId, Instant decidedAt, boolean replayed) {
    }
}
