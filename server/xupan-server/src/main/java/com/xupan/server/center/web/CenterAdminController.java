package com.xupan.server.center.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.service.CenterAgentService;
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

    public CenterAdminController(CenterAgentService service) {
        this.service = service;
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
