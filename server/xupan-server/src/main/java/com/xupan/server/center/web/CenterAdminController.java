package com.xupan.server.center.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.repository.CenterOrderRepository;
import com.xupan.server.center.service.CenterAgentService;
import com.xupan.server.center.service.CenterOrderService;
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
    private final PermissionService permissionService;

    public CenterAdminController(CenterAgentService service, CenterOrderService orderService,
                                 PermissionService permissionService) {
        this.service = service;
        this.orderService = orderService;
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

    public record CenterAgentRequest(@NotBlank String agentCode, @NotBlank String agentName,
                                     @NotBlank String upstreamAccount, BigDecimal creditLimit) {
    }

    public record CenterAgentStatusRequest(@NotBlank String status) {
    }

    public record CenterGrantRequest(BigDecimal amount, String reason, @NotBlank String idempotencyKey) {
    }

    public record CenterCreditLimitRequest(BigDecimal creditLimit, @NotBlank String idempotencyKey) {
    }

    /** 中心代理（**不含** Token 原文，只含可展示前缀）。 */
    public record CenterAgentResponse(long id, String agentCode, String agentName, String upstreamAccount,
                                      String tokenPrefix, String status, BigDecimal creditLimit,
                                      BigDecimal availableBalance, BigDecimal frozenBalance,
                                      BigDecimal upstreamUnsettled, Instant createdAt, Instant updatedAt) {
        static CenterAgentResponse from(CenterAgentRepository.AgentRow row) {
            return new CenterAgentResponse(row.id(), row.agentCode(), row.agentName(), row.upstreamAccount(),
                    row.tokenPrefix(), row.status(), row.creditLimit(), row.availableBalance(),
                    row.frozenBalance(), row.upstreamUnsettled(), row.createdAt(), row.updatedAt());
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
