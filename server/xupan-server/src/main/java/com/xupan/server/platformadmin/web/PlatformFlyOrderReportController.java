package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformFlyOrderReportService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/reports")
public class PlatformFlyOrderReportController {

    private final PlatformFlyOrderReportService service;

    public PlatformFlyOrderReportController(PlatformFlyOrderReportService service) {
        this.service = service;
    }

    @GetMapping("/fly-orders")
    public FlyOrderPageResponse flyOrders(Authentication authentication,
                                          @RequestParam(required = false) String day,
                                          @RequestParam(defaultValue = "6") int cutoffHour,
                                          @RequestParam(defaultValue = "REPORTED") String status,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int pageSize,
                                          @RequestParam(required = false) Long subAccountId) {
        return FlyOrderPageResponse.from(service.flyOrders(day, cutoffHour, status, page, pageSize,
                subAccountId, operator(authentication).getUserId()));
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record SummaryResponse(long unreportedCount, BigDecimal unreportedAmount,
                                  long reportedCount, BigDecimal reportedAmount,
                                  long failedCount, BigDecimal failedAmount) {
        static SummaryResponse from(PlatformFlyOrderReportService.Summary summary) {
            return new SummaryResponse(summary.unreportedCount(), summary.unreportedAmount(),
                    summary.reportedCount(), summary.reportedAmount(), summary.failedCount(), summary.failedAmount());
        }
    }

    public record FlyOrderResponse(long betId, Instant createdAt, String memberCode, String playerName,
                                   String issueNumber, int ballNumber, String playType, String parametersText,
                                   BigDecimal stake, BigDecimal odds, String reportStatus, String responseCode) {
        static FlyOrderResponse from(PlatformFlyOrderReportService.FlyOrderRow row) {
            return new FlyOrderResponse(row.betId(), row.createdAt(), row.memberCode(), row.playerName(),
                    row.issueNumber(), row.ballNumber(), row.playType(), row.parametersText(), row.stake(),
                    row.odds(), row.reportStatus(), row.responseCode());
        }
    }

    public record FlyOrderPageResponse(LocalDate day, int cutoffHour, String status, int page, int pageSize,
                                       long total, SummaryResponse summary, List<FlyOrderResponse> items) {
        static FlyOrderPageResponse from(PlatformFlyOrderReportService.FlyOrderPage page) {
            return new FlyOrderPageResponse(page.day(), page.cutoffHour(), page.status(), page.page(),
                    page.pageSize(), page.total(), SummaryResponse.from(page.summary()),
                    page.items().stream().map(FlyOrderResponse::from).toList());
        }
    }
}
