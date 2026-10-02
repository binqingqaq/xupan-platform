package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformUnsettledOrderService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/unsettled-orders")
public class PlatformUnsettledOrderController {

    private final PlatformUnsettledOrderService service;

    public PlatformUnsettledOrderController(PlatformUnsettledOrderService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse list(Authentication authentication,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "30") int pageSize) {
        return PageResponse.from(service.list(page, pageSize, operator(authentication).getUserId()));
    }

    @DeleteMapping("/{id}")
    public CancellationResponse cancel(Authentication authentication, @PathVariable long id) {
        return CancellationResponse.from(service.cancel(id, operator(authentication).getUserId()));
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record PageResponse(List<OrderResponse> items, int page, int pageSize, long total) {
        static PageResponse from(PlatformUnsettledOrderService.Page page) {
            return new PageResponse(page.items().stream().map(OrderResponse::from).toList(),
                    page.page(), page.pageSize(), page.total());
        }
    }

    public record OrderResponse(long id, String subAccount, String machineName, String memberCode,
                                String playerName, String issueNumber, String command,
                                Instant createdAt, String reportStatus) {
        static OrderResponse from(PlatformUnsettledOrderService.Row row) {
            return new OrderResponse(row.id(), row.subAccount(), row.machineName(), row.memberCode(),
                    row.playerName(), row.issueNumber(), row.command(), row.createdAt(), row.reportStatus());
        }
    }

    public record CancellationResponse(long id, String status, BigDecimal refundedAmount) {
        static CancellationResponse from(PlatformUnsettledOrderService.CancellationResult result) {
            return new CancellationResponse(result.id(), result.status(), result.refundedAmount());
        }
    }
}
