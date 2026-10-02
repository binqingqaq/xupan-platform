package com.xupan.server.center.web;

import com.xupan.server.center.repository.CenterAgentRepository;
import com.xupan.server.center.repository.CenterOrderRepository;
import com.xupan.server.center.service.CenterOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 中心上游接单接口（**代理面**，用代理 Token 认证，不是会话认证）。
 *
 * <p>这项按《代理报网盘口与结算业务规则说明》§16.3：订单上报到中心；
 * 中心按 §16.4 决定接受或拒绝。调用方是"代理系统"，因此不能用超级后台的会话，
 * 而是用 {@code Authorization: Bearer <代理 Token>}。</p>
 *
 * <p>幂等：{@code agentOrderNo} 是代理侧订单号，同一代理下重复上报只受理一次，
 * 重复请求返回首次结果（{@code duplicate=true}）。</p>
 */
@RestController
@RequestMapping("/api/center")
public class CenterOrderController {

    private final CenterOrderService orderService;

    public CenterOrderController(CenterOrderService orderService) {
        this.orderService = orderService;
    }

    /** 当前 Token 对应的中心代理身份（代理可用它自检 Token 是否有效）。 */
    @GetMapping("/me")
    public CenterAgentSelf me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        CenterAgentRepository.AgentRow agent = orderService.authenticate(bearer(authorization));
        return new CenterAgentSelf(agent.id(), agent.agentCode(), agent.agentName(), agent.upstreamAccount(),
                agent.status(), agent.creditLimit(), agent.availableBalance(), agent.frozenBalance(),
                agent.upstreamUnsettled());
    }

    /**
     * 上报一笔订单。
     *
     * <p>返回体区分 {@code ACCEPTED} 与 {@code REJECTED}：拒绝也返回 200 并带上
     * {@code rejectCode}/{@code rejectReason}，因为"被拒绝"是业务的正常分支，
     * 代理侧要据此决定是否失败退单（§12.4），而不是把它当成传输错误重试。</p>
     */
    @PostMapping("/orders")
    public AcceptedOrder accept(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CenterOrderRequest request) {
        CenterAgentRepository.AgentRow agent = orderService.authenticate(bearer(authorization));
        CenterOrderService.Accepted accepted = orderService.accept(agent.id(), request.agentOrderNo(),
                request.localBetId(), request.localAgentId(), request.gameCode(), request.issueNumber(),
                request.playType(), request.playText(), request.odds(), request.amount());
        return AcceptedOrder.from(accepted);
    }

    /** 本代理的中心订单（对账用；只看得到自己的订单）。 */
    @GetMapping("/orders")
    public CenterOrderPage orders(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        CenterAgentRepository.AgentRow agent = orderService.authenticate(bearer(authorization));
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw com.xupan.server.web.BusinessException.badRequest(
                    "CENTER_ORDER_QUERY_INVALID", "分页参数必须为 1 到 100");
        }
        List<CenterOrderRepository.OrderRow> items = orderService.list(agent.id(), status, page, pageSize);
        return new CenterOrderPage(items.stream().map(CenterOrderView::from).toList(), page, pageSize,
                orderService.count(agent.id(), status));
    }

    /** 从 {@code Authorization} 头取 Token。 */
    private static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }

    public record CenterOrderRequest(@NotBlank String agentOrderNo, Long localBetId, Long localAgentId,
                                     @NotBlank String gameCode, @NotBlank String issueNumber,
                                     @NotBlank String playType, String playText, BigDecimal odds,
                                     BigDecimal amount) {
    }

    /** 代理自检视图（不含 Token 原文）。 */
    public record CenterAgentSelf(long id, String agentCode, String agentName, String upstreamAccount,
                                  String status, BigDecimal creditLimit, BigDecimal availableBalance,
                                  BigDecimal frozenBalance, BigDecimal upstreamUnsettled) {
    }

    public record AcceptedOrder(long orderId, String status, String rejectCode, String rejectReason,
                                boolean duplicate, BigDecimal frozenAmount) {
        static AcceptedOrder from(CenterOrderService.Accepted accepted) {
            return new AcceptedOrder(accepted.orderId(), accepted.status(), accepted.rejectCode(),
                    accepted.rejectReason(), accepted.duplicate(), accepted.frozenAmount());
        }
    }

    public record CenterOrderPage(List<CenterOrderView> items, int page, int pageSize, long total) {
    }

    public record CenterOrderView(long id, String agentOrderNo, Long localBetId, String gameCode,
                                  String issueNumber, String playType, String playText, BigDecimal odds,
                                  BigDecimal amount, String status, String rejectCode, String rejectReason,
                                  BigDecimal frozenAmount, String settleResult, BigDecimal settleAmount,
                                  String drawNumbers, Instant acceptedAt, Instant settledAt, Instant createdAt) {
        static CenterOrderView from(CenterOrderRepository.OrderRow row) {
            return new CenterOrderView(row.id(), row.agentOrderNo(), row.localBetId(), row.gameCode(),
                    row.issueNumber(), row.playType(), row.playText(), row.odds(), row.amount(), row.status(),
                    row.rejectCode(), row.rejectReason(), row.frozenAmount(), row.settleResult(),
                    row.settleAmount(), row.drawNumbers(), row.acceptedAt(), row.settledAt(), row.createdAt());
        }
    }
}
