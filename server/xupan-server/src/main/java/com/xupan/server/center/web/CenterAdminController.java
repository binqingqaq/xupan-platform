package com.xupan.server.center.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.repository.CenterOrderRepository;
import com.xupan.server.center.repository.ReconciliationDispositionRepository;
import com.xupan.server.center.service.CenterAgentService;
import com.xupan.server.center.service.CenterOrderService;
import com.xupan.server.center.service.CenterReconciliationService;
import com.xupan.server.center.service.CenterSettlementService;
import com.xupan.server.center.service.ReconciliationDispositionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 中心上游管理接口（超管面）。
 *
 * <p>对应《代理报网盘口与结算业务规则说明》§23.7 的"可运营"档：中心侧先提供 API，
 * 中心管理端菜单在后续阶段接入（本阶段先把数据与账本做对）。</p>
 *
 * <p>额度三层（原则 1）：{@code availableBalance} / {@code frozenBalance} / {@code upstreamUnsettled}
 * 是三个不同事实，分开返回、分开记账。</p>
 */
@RestController
@RequestMapping("/api/admin/center/agents")
public class CenterAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CenterAgentService service;
    private final CenterOrderService orderService;
    private final CenterSettlementService settlementService;
    private final CenterReconciliationService reconciliationService;
    private final PermissionService permissionService;

    public CenterAdminController(CenterAgentService service, CenterOrderService orderService,
                                 CenterSettlementService settlementService,
                                 CenterReconciliationService reconciliationService,
                                 PermissionService permissionService) {
        this.service = service;
        this.orderService = orderService;
        this.settlementService = settlementService;
        this.reconciliationService = reconciliationService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public List<CenterAgentResponse> list(Authentication authentication) {
        return service.list(operator(authentication)).stream().map(CenterAgentResponse::from).toList();
    }

    /**
     * 创建中心代理。
     *
     * <p>响应里的 {@code token} 是**唯一一次**返回的 Token 原文（原则 9）：
     * 库里只保存 SHA-256 哈希，之后任何接口都拿不到原文。</p>
     */
    @PostMapping
    public CenterAgentCreatedResponse create(Authentication authentication,
                                             @Valid @RequestBody CenterAgentRequest request) {
        CenterAgentService.Created created = service.create(request.agentCode(), request.agentName(),
                request.upstreamAccount(), request.creditLimit(), operator(authentication));
        return new CenterAgentCreatedResponse(CenterAgentResponse.from(created.agent()), created.token());
    }

    @PatchMapping("/{id}/status")
    public CenterAgentResponse changeStatus(Authentication authentication, @PathVariable long id,
                                            @Valid @RequestBody CenterAgentStatusRequest request) {
        service.changeStatus(id, request.status(), operator(authentication));
        return CenterAgentResponse.from(service.require(id));
    }

    /** 给可用额度充值（虚拟积分）。 */
    @PostMapping("/{id}/grants")
    public CenterAgentResponse grant(Authentication authentication, @PathVariable long id,
                                     @Valid @RequestBody CenterGrantRequest request) {
        service.grantAvailable(id, request.amount(), request.reason(), operator(authentication),
                request.idempotencyKey());
        return CenterAgentResponse.from(service.require(id));
    }

    /** 直接设定信用额度。 */
    @PostMapping("/{id}/credit-limit")
    public CenterAgentResponse setCreditLimit(Authentication authentication, @PathVariable long id,
                                              @Valid @RequestBody CenterCreditLimitRequest request) {
        service.setCreditLimit(id, request.creditLimit(), operator(authentication), request.idempotencyKey());
        return CenterAgentResponse.from(service.require(id));
    }

    /**
     * 设定**代理级风控上限**（P1-C1）。传 {@code null} 表示回退到全局默认。
     *
     * <p>为什么需要：阶段 B 的限额是全局常量，一个代理就能吃掉整期敞口。</p>
     */
    @PatchMapping("/{id}/risk-limits")
    public CenterAgentResponse setRiskLimits(Authentication authentication, @PathVariable long id,
                                             @RequestBody CenterRiskLimitRequest request) {
        return CenterAgentResponse.from(service.setRiskLimits(id, request.maxSingleAmount(),
                request.maxIssueExposure(), operator(authentication)));
    }

    /** 某代理最近的风控命中记录（管理端"风险"页下钻）。 */
    @GetMapping("/{id}/risk-hits")
    public List<RiskHitView> riskHits(Authentication authentication, @PathVariable long id,
                                      @RequestParam(defaultValue = "20") int limit) {
        requireAgentManager(authentication);
        if (limit < 1 || limit > 200) {
            throw com.xupan.server.web.BusinessException.badRequest(
                    "CENTER_RISK_QUERY_INVALID", "limit 必须为 1 到 200");
        }
        return service.recentRiskHits(id, limit).stream().map(RiskHitView::from).toList();
    }

    /** 全量风控命中按规则聚合（管理端"风险"页总览）。 */
    @GetMapping("/risk-hits/summary")
    public List<RiskRuleSummary> riskHitSummary(Authentication authentication,
                                                @RequestParam(defaultValue = "1440") int windowMinutes) {
        requireOrderReader(authentication);
        if (windowMinutes < 1 || windowMinutes > 43200) {
            throw com.xupan.server.web.BusinessException.badRequest(
                    "CENTER_RISK_QUERY_INVALID", "windowMinutes 必须为 1 到 43200");
        }
        return service.riskHitSummary(java.time.Instant.now()
                .minusSeconds(windowMinutes * 60L)).stream().map(RiskRuleSummary::from).toList();
    }

    // ---------------------------------------------------------------------
    // C-2 / C-3：中心侧订单处置（超时确认、失败确认、退款、撤单）
    // ---------------------------------------------------------------------

    /**
     * 处置一笔中心订单（C-2）：确认受理 / 确认失败 / 退款 / 撤销。
     *
     * <p>这是"超时不等于失败"（需求 §12.3）在中心侧的落地：超时单必须由人工查证后
     * 明确落到某个终态，而不是永远挂着。每个动作都带幂等键与审计。</p>
     */
    @PostMapping("/orders/{orderId}/disposition")
    public CenterOrderDispositionResponse dispose(Authentication authentication, @PathVariable long orderId,
                                                  @Valid @RequestBody CenterOrderDispositionRequest request) {
        CenterOrderService.DispositionResult result = orderService.dispose(orderId, request.action(),
                request.reason(), request.idempotencyKey(), operator(authentication));
        return new CenterOrderDispositionResponse(result.orderId(), result.status(), result.action(),
                result.frozenAmount(), result.settleAmount(), result.duplicate());
    }

    /** 中心订单的处置历史（审计回查）。 */
    @GetMapping("/orders/{orderId}/dispositions")
    public List<CenterOrderDispositionView> dispositions(Authentication authentication,
                                                         @PathVariable long orderId) {
        requireOrderReader(authentication);
        return orderService.dispositionHistory(orderId).stream()
                .map(CenterOrderDispositionView::from).toList();
    }

    // ---------------------------------------------------------------------
    // C-2 ② / C-4 ③：对账差异的处置与保留策略
    // ---------------------------------------------------------------------

    /**
     * 处置一条对账差异。
     *
     * <p><b>只记录判断，不修复数据。</b>规划明确"差异应先人工确认再自动化"，
     * 自动改账会在真相未明时改写账本事实（这比差异本身更危险）。</p>
     */
    @PostMapping("/reconciliation/dispositions")
    public ReconciliationDispositionResponse disposeDifference(
            Authentication authentication,
            @Valid @RequestBody ReconciliationDispositionRequest request) {
        requireOrderReader(authentication);
        // missingInLocal 这类差异代表"同一注单在两侧金额/状态不一致"，因此必须带注单号。
        // 注单号在两侧都是可核对的锚点（本地 bet_id ↔ 中心 agent_order_no = BET-<betId>）。
        ReconciliationDispositionService.DispositionResult result =
                reconciliationService.disposeDifference(request.differenceType(), request.betId(),
                        request.resolution(), request.centerAgentId(), request.reason(),
                        request.effectiveDays() == null ? null
                                : java.time.Duration.ofDays(request.effectiveDays()),
                        request.idempotencyKey(), operator(authentication));
        return ReconciliationDispositionResponse.from(result);
    }

    /** 撤销一条差异处置：差异重新回到待处置列表，判断历史保留。 */
    @PostMapping("/reconciliation/dispositions/{id}/revoke")
    public ReconciliationDispositionResponse revokeDifferenceDisposition(
            Authentication authentication, @PathVariable long id) {
        requireOrderReader(authentication);
        return ReconciliationDispositionResponse.from(
                reconciliationService.revokeDifferenceDisposition(id, operator(authentication)));
    }

    /** 当前生效的差异处置列表。 */
    @GetMapping("/reconciliation/dispositions")
    public List<ReconciliationDispositionView> effectiveDispositions(
            Authentication authentication, @RequestParam(defaultValue = "50") int limit) {
        requireOrderReader(authentication);
        return reconciliationService.effectiveDispositions(limit).stream()
                .map(ReconciliationDispositionView::from).toList();
    }

    /**
     * 保留策略观察值（C-4 ③）：生效中与已失效的处置记录数量。
     *
     * <p>处置记录**不自动删除**——它是"差异为什么被认可"的凭据。
     * 本接口只用于运维判断归档时机。</p>
     */
    @GetMapping("/reconciliation/retention")
    public ReconciliationRetentionResponse retention(Authentication authentication) {
        requireOrderReader(authentication);
        ReconciliationDispositionService.RetentionStats stats =
                reconciliationService.retentionStats();
        return new ReconciliationRetentionResponse(stats.effective(), stats.expired());
    }

    /**
     * 中心订单列表（中心管理端的"实时订单"）。
     *
     * <p>按《代理报网盘口与结算业务规则说明》§23.7，运营档需要有中心订单可见性；
     * 这里先提供数据接口，菜单在阶段 C 一起接入。</p>
     */
    @GetMapping("/orders")
    public CenterOrderPage orders(Authentication authentication,
                                  @RequestParam(required = false) Long centerAgentId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int pageSize) {
        requireOrderReader(authentication);
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw com.xupan.server.web.BusinessException.badRequest(
                    "CENTER_ORDER_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        return new CenterOrderPage(orderService.list(centerAgentId, status, page, pageSize).stream()
                .map(CenterOrderView::from).toList(), page, pageSize,
                orderService.count(centerAgentId, status));
    }

    /**
     * 第二层结算：按开奖结果结算某游戏某期全部已接受的中心订单（中心赔代理 / 从代理收）。
     *
     * <p>对应《代理报网盘口与结算业务规则说明》§10：玩家赢 → 本地赔玩家、中心再赔代理；
     * 玩家输 → 本地收玩家、中心再从代理收。幂等：只处理 ACCEPTED，结算后置为 SETTLED。</p>
     */
    @PostMapping("/settlements")
    public CenterSettlementResponse settle(Authentication authentication,
                                           @Valid @RequestBody CenterSettlementRequest request) {
        long operator = operator(authentication);
        if (!permissionService.hasPermission(operator, "CENTER_AGENT_MANAGE")) {
            throw com.xupan.server.web.BusinessException.forbidden("CENTER_AGENT_FORBIDDEN",
                    "没有中心上游管理权限");
        }
        com.xupan.server.game.domain.BallResult result = new com.xupan.server.game.domain.BallResult(
                request.number(), request.fan(), request.parity(), request.size());
        CenterSettlementService.SettlementSummary summary = settlementService.settleIssue(
                request.gameCode(), request.issueNumber(), result, request.drawNumbers(),
                request.limit() == null ? 500 : request.limit());
        return CenterSettlementResponse.from(summary);
    }

    /**
     * 两侧对账：本地上报订单 ↔ 中心订单逐笔比对，并做双方账本自检。
     *
     * <p>对应闭环判据"没有超时/退款/对账遗留"：{@code balanced=true} 表示已对平。</p>
     */
    @GetMapping("/reconciliation")
    public ReconciliationResponse reconciliation(Authentication authentication,
                                                 @RequestParam(required = false) Long machineId,
                                                 @RequestParam(defaultValue = "50") int limit) {
        requireOrderReader(authentication);
        if (limit < 1 || limit > 500) {
            throw com.xupan.server.web.BusinessException.badRequest(
                    "CENTER_RECONCILIATION_QUERY_INVALID", "limit 必须为 1 到 500");
        }
        CenterReconciliationService.Report report = reconciliationService.reconcile(machineId, limit);
        return ReconciliationResponse.from(report, reconciliationService.countInconsistentCenterBuckets(),
                reconciliationService.countInconsistentAgentBuckets());
    }

    /** 中心订单按状态的汇总（中心总览）。 */
    @GetMapping("/orders/summary")
    public List<CenterStatusSummary> ordersSummary(Authentication authentication) {
        requireOrderReader(authentication);
        return orderService.countsByStatus().stream().map(CenterStatusSummary::from).toList();
    }

    /** 中心代理账本流水（追加式，含变动前后值）。 */
    @GetMapping("/{id}/ledger")
    public CenterLedgerPage ledger(Authentication authentication, @PathVariable long id,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "20") int pageSize) {
        operator(authentication);
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw com.xupan.server.web.BusinessException.badRequest(
                    "CENTER_LEDGER_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        service.require(id);
        return new CenterLedgerPage(service.ledger(id, page, pageSize).stream()
                .map(CenterLedgerEntry::from).toList(), page, pageSize, service.ledgerCount(id),
                service.ledgerBalanced(id));
    }

    /**
     * 解析操作者。
     *
     * <p>与超级后台其它控制器一致：控制器只负责"取到登录身份"，权限判定交给服务层的
     * {@code PermissionService}（见 {@code CenterAgentService} 的 {@code requireManage}），
     * 避免同一个权限在两处各判一次、口径漂移。</p>
     */
    /**
     * 订单查看权限：{@code CENTER_ORDER_READ}。
     *
     * <p>与 {@code CENTER_AGENT_MANAGE} 分开：允许"只看订单不看账户与额度"的角色存在。</p>
     */
    private void requireOrderReader(Authentication authentication) {
        long operator = operator(authentication);
        if (!permissionService.hasPermission(operator, "CENTER_ORDER_READ")) {
            throw com.xupan.server.web.BusinessException.forbidden("CENTER_ORDER_FORBIDDEN",
                    "没有中心上游订单查看权限");
        }
    }

    private long operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user.getUserId();
    }

    /** 账户管理权限：{@code CENTER_AGENT_MANAGE}（风控配置等写操作）。 */
    private void requireAgentManager(Authentication authentication) {
        long operator = operator(authentication);
        if (!permissionService.hasPermission(operator, "CENTER_AGENT_MANAGE")) {
            throw com.xupan.server.web.BusinessException.forbidden("CENTER_AGENT_FORBIDDEN",
                    "没有中心上游管理权限");
        }
    }

    public record CenterAgentRequest(@NotBlank String agentCode, @NotBlank String agentName,
                                     @NotBlank String upstreamAccount, BigDecimal creditLimit) {
    }

    public record CenterAgentStatusRequest(@NotBlank String status) {
    }

    public record CenterGrantRequest(BigDecimal amount, String reason, @NotBlank String idempotencyKey) {
    }

    public record CenterCreditLimitRequest(BigDecimal creditLimit, @NotBlank String idempotencyKey) {
    }

    /** 代理级风控上限；缺省或为 {@code null} 表示回退全局默认。 */
    public record CenterRiskLimitRequest(BigDecimal maxSingleAmount, BigDecimal maxIssueExposure) {
    }

    /** 一次风控命中（管理端展示）。 */
    public record RiskHitView(long id, String ruleCode, String ruleCategory, BigDecimal observedValue,
                              BigDecimal thresholdValue, String gameCode, String issueNumber,
                              String agentOrderNo, String detail, Instant createdAt) {
        static RiskHitView from(CenterAgentRepository.RiskHitRow row) {
            return new RiskHitView(row.id(), row.ruleCode(), row.ruleCategory(), row.observedValue(),
                    row.thresholdValue(), row.gameCode(), row.issueNumber(), row.agentOrderNo(),
                    row.detail(), row.createdAt());
        }
    }

    /** 按规则聚合的风控命中。 */
    public record RiskRuleSummary(String ruleCode, String ruleCategory, long hitCount, long agentCount) {
        static RiskRuleSummary from(CenterAgentRepository.RiskRuleCount count) {
            return new RiskRuleSummary(count.ruleCode(), count.ruleCategory(), count.hitCount(),
                    count.agentCount());
        }
    }

    /**
     * 中心订单处置请求（C-2 / C-3）。
     *
     * <p>{@code action} 取值为 {@code CONFIRM_ACCEPTED} / {@code CONFIRM_FAILED} /
     * {@code REFUND} / {@code CANCEL}，语义见 {@code CenterOrderService#dispose}。</p>
     */
    public record CenterOrderDispositionRequest(@NotBlank String action, String reason,
                                                @NotBlank String idempotencyKey) {
    }

    public record CenterOrderDispositionResponse(long orderId, String status, String action,
                                                 BigDecimal frozenAmount, BigDecimal settleAmount,
                                                 boolean duplicate) {
    }

    public record CenterOrderDispositionView(long id, long centerOrderId, String action, String fromStatus,
                                             String toStatus, BigDecimal amount, String reason,
                                             Long operatorUserId, Instant createdAt) {
        static CenterOrderDispositionView from(CenterOrderRepository.DispositionRow row) {
            return new CenterOrderDispositionView(row.id(), row.centerOrderId(), row.action(),
                    row.fromStatus(), row.toStatus(), row.amount(), row.reason(), row.operatorUserId(),
                    row.createdAt());
        }
    }

    /** 对账差异处置请求。{@code effectiveDays} 为空时用默认 7 天（到期后差异重新待处置）。 */
    public record ReconciliationDispositionRequest(@NotBlank String differenceType, long betId,
                                                   @NotBlank String resolution, Long centerAgentId,
                                                   String reason, Integer effectiveDays,
                                                   @NotBlank String idempotencyKey) {
    }

    public record ReconciliationDispositionResponse(long id, String differenceType, long betId,
                                                    String resolution, String reason, Instant expiresAt,
                                                    Instant revokedAt, boolean duplicate) {
        static ReconciliationDispositionResponse from(
                ReconciliationDispositionService.DispositionResult result) {
            return new ReconciliationDispositionResponse(result.id(), result.differenceType(),
                    result.betId(), result.resolution(), result.reason(), result.expiresAt(),
                    result.revokedAt(), result.duplicate());
        }
    }

    public record ReconciliationDispositionView(long id, String differenceType, long betId,
                                                String resolution, Long centerAgentId, String reason,
                                                Instant expiresAt, Instant revokedAt, Instant createdAt) {
        static ReconciliationDispositionView from(ReconciliationDispositionRepository.Row row) {
            return new ReconciliationDispositionView(row.id(), row.differenceType(), row.betId(),
                    row.resolution(), row.centerAgentId(), row.reason(), row.expiresAt(),
                    row.revokedAt(), row.createdAt());
        }
    }

    /** 保留策略观察值。 */
    public record ReconciliationRetentionResponse(long effective, long expired) {
    }

    /** 中心代理（**不含** Token 原文，只含可展示前缀）。 */
    public record CenterAgentResponse(long id, String agentCode, String agentName, String upstreamAccount,
                                      String tokenPrefix, String status, BigDecimal creditLimit,
                                      BigDecimal availableBalance, BigDecimal frozenBalance,
                                      BigDecimal upstreamUnsettled,
                                      BigDecimal maxSingleAmount, BigDecimal maxIssueExposure,
                                      String lastRejectCode, Instant lastRejectAt, long rejectCount,
                                      Instant createdAt, Instant updatedAt) {
        static CenterAgentResponse from(CenterAgentRepository.AgentRow row) {
            return new CenterAgentResponse(row.id(), row.agentCode(), row.agentName(), row.upstreamAccount(),
                    row.tokenPrefix(), row.status(), row.creditLimit(), row.availableBalance(),
                    row.frozenBalance(), row.upstreamUnsettled(),
                    row.maxSingleAmount(), row.maxIssueExposure(), row.lastRejectCode(),
                    row.lastRejectAt(), row.rejectCount(), row.createdAt(), row.updatedAt());
        }
    }

    /** 创建响应：额外带**一次性**的 Token 原文。 */
    public record CenterAgentCreatedResponse(CenterAgentResponse agent, String token) {
    }

    public record CenterOrderPage(List<CenterOrderView> items, int page, int pageSize, long total) {
    }

    public record CenterOrderView(long id, long centerAgentId, String agentOrderNo, Long localBetId,
                                  String gameCode, String issueNumber, String playType, String playText,
                                  BigDecimal odds, BigDecimal amount, String status, String rejectCode,
                                  String rejectReason, BigDecimal frozenAmount, String settleResult,
                                  BigDecimal settleAmount, String drawNumbers, Instant acceptedAt,
                                  Instant settledAt, Instant createdAt) {
        static CenterOrderView from(CenterOrderRepository.OrderRow row) {
            return new CenterOrderView(row.id(), row.centerAgentId(), row.agentOrderNo(), row.localBetId(),
                    row.gameCode(), row.issueNumber(), row.playType(), row.playText(), row.odds(),
                    row.amount(), row.status(), row.rejectCode(), row.rejectReason(), row.frozenAmount(),
                    row.settleResult(), row.settleAmount(), row.drawNumbers(), row.acceptedAt(),
                    row.settledAt(), row.createdAt());
        }
    }

    /** 按状态的汇总。 */
    public record CenterStatusSummary(String status, long total, BigDecimal amount) {
        static CenterStatusSummary from(CenterOrderRepository.StatusCount count) {
            return new CenterStatusSummary(count.status(), count.total(), count.amount());
        }
    }

    /**
     * 第二层结算请求。
     *
     * <p>开奖字段用包装类型而不是 {@code int} + {@code @NotBlank}：
     * 缺字段时应该由业务层给出可读错误（"开奖结果不完整"），
     * 而不是被 Bean Validation 提前拦成一个笼统的 {@code REQUEST_INVALID}。</p>
     */
    public record CenterSettlementRequest(@NotBlank String gameCode, @NotBlank String issueNumber,
                                          Integer number, Integer fan, String parity, String size,
                                          String drawNumbers, Integer limit) {
    }

    /** 一期第二层结算结果。 */
    public record CenterSettlementResponse(String gameCode, String issueNumber, int settledCount,
                                           int skippedCount, BigDecimal agentWinTotal,
                                           BigDecimal agentLoseTotal, List<SettledOrderView> orders) {
        static CenterSettlementResponse from(CenterSettlementService.SettlementSummary summary) {
            return new CenterSettlementResponse(summary.gameCode(), summary.issueNumber(),
                    summary.settledCount(), summary.skippedCount(), summary.agentWinTotal(),
                    summary.agentLoseTotal(), summary.orders().stream().map(SettledOrderView::from).toList());
        }
    }

    public record SettledOrderView(long orderId, long centerAgentId, String agentOrderNo,
                                   String settleResult, BigDecimal settleAmount, BigDecimal stake,
                                   String label) {
        static SettledOrderView from(CenterSettlementService.SettledOrder order) {
            return new SettledOrderView(order.orderId(), order.centerAgentId(), order.agentOrderNo(),
                    order.settleResult(), order.settleAmount(), order.stake(), order.label());
        }
    }

    /** 对账结果。 */
    public record ReconciliationResponse(ReconciliationCounts counts, int differenceCount, boolean balanced,
                                         List<ReconciliationDifference> differences,
                                         int missingInCenterCount, int missingInLocalCount,
                                         int statusMismatchCount, int amountMismatchCount,
                                         long inconsistentCenterBuckets, long inconsistentAgentBuckets) {
        static ReconciliationResponse from(CenterReconciliationService.Report report,
                                           long inconsistentCenterBuckets, long inconsistentAgentBuckets) {
            return new ReconciliationResponse(
                    new ReconciliationCounts(report.counts().localTotal(), report.counts().centerTotal(),
                            report.counts().localUnresolved(), report.counts().centerUnresolved(),
                            report.counts().activeCenterAgents()),
                    report.differenceCount(), report.balanced(),
                    report.differences().stream().map(ReconciliationDifference::from).toList(),
                    report.missingInCenterCount(), report.missingInLocalCount(),
                    report.statusMismatchCount(), report.amountMismatchCount(),
                    inconsistentCenterBuckets, inconsistentAgentBuckets);
        }
    }

    public record ReconciliationCounts(long localTotal, long centerTotal, long localUnresolved,
                                       long centerUnresolved, long activeCenterAgents) {
    }

    public record ReconciliationDifference(String type, long betId, String localStatus, String centerStatus,
                                           BigDecimal localAmount, BigDecimal centerAmount) {
        static ReconciliationDifference from(CenterReconciliationService.Difference difference) {
            return new ReconciliationDifference(difference.type(), difference.betId(),
                    difference.localStatus(), difference.centerStatus(), difference.localAmount(),
                    difference.centerAmount());
        }
    }

    /** 一页中心账本。 */
    public record CenterLedgerPage(List<CenterLedgerEntry> items, int page, int pageSize, long total,
                                   boolean balanced) {
    }

    public record CenterLedgerEntry(long id, String bucket, String entryType, String direction,
                                    BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter,
                                    String reason, String referenceType, String referenceId, Instant createdAt) {
        static CenterLedgerEntry from(CenterAgentRepository.LedgerRow row) {
            return new CenterLedgerEntry(row.id(), row.bucket(), row.entryType(), row.direction(), row.amount(),
                    row.balanceBefore(), row.balanceAfter(), row.reason(), row.referenceType(),
                    row.referenceId(), row.createdAt());
        }
    }
}
