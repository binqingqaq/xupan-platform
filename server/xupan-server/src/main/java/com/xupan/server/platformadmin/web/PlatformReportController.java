package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformReportService;
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
public class PlatformReportController {

    private final PlatformReportService service;

    public PlatformReportController(PlatformReportService service) {
        this.service = service;
    }

    @GetMapping("/score-flow")
    public ScoreFlowPageResponse scoreFlow(Authentication authentication,
                                           @RequestParam(required = false) String day,
                                           @RequestParam(required = false) String day3,
                                           @RequestParam(required = false) Long subAccountId,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int pageSize) {
        return ScoreFlowPageResponse.from(service.scoreFlow(day, day3, subAccountId,
                page, pageSize, operator(authentication).getUserId()));
    }

    @GetMapping("/profit")
    public ProfitReportResponse profit(Authentication authentication,
                                       @RequestParam(required = false) String day,
                                       @RequestParam(required = false) String day3,
                                       @RequestParam(required = false) Long subAccountId) {
        return ProfitReportResponse.from(service.profitReport(day, day3, subAccountId,
                operator(authentication).getUserId()));
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record ScoreFlowPageResponse(List<PlatformReportService.ScoreFlowPlayerRow> items, int page,
                                        int pageSize, long total,
                                        PlatformReportService.ScoreFlowSummary summary) {
        static ScoreFlowPageResponse from(PlatformReportService.ScoreFlowPage page) {
            return new ScoreFlowPageResponse(page.items(), page.page(), page.pageSize(), page.total(), page.summary());
        }
    }

    public record ProfitReportResponse(String day, String day3, Instant fromInclusive, Instant toExclusive,
                                       List<MachineProfitResponse> machines, BigDecimal totalRemaining,
                                       BigDecimal totalFlow, BigDecimal totalSingleFlow,
                                       BigDecimal totalDoubleFlow, BigDecimal totalProfit,
                                       BigDecimal totalFanShui, BigDecimal totalRebateDouble,
                                       BigDecimal totalEffectiveRebate, BigDecimal totalUp, BigDecimal totalDown,
                                       BigDecimal totalUpDown) {
        static ProfitReportResponse from(PlatformReportService.ProfitReport report) {
            return new ProfitReportResponse(report.day().toString(), report.day3().toString(),
                    report.fromInclusive(), report.toExclusive(),
                    report.machines().stream().map(MachineProfitResponse::from).toList(),
                    report.totalRemaining(), report.totalFlow(), report.totalSingleFlow(), report.totalDoubleFlow(),
                    report.totalProfit(), report.totalFanShui(), report.totalRebateDouble(),
                    report.totalEffectiveRebate(), report.totalUp(),
                    report.totalDown(), report.totalUpDown());
        }
    }

    public record MachineProfitResponse(long machineId, String machineCode, String machineName, Long groupId,
                                        String groupUsername, BigDecimal score, BigDecimal playerBalance,
                                        BigDecimal totalRemaining,
                                        BigDecimal totalFlow, BigDecimal totalSingleFlow, BigDecimal totalDoubleFlow, BigDecimal totalProfit,
                                        BigDecimal totalFanShui, BigDecimal rebateDouble,
                                        BigDecimal totalUp, BigDecimal totalDown,
                                        String rebateMode, BigDecimal effectiveRebate) {
        static MachineProfitResponse from(PlatformReportService.MachineProfitRow row) {
            return new MachineProfitResponse(row.machineId(), row.machineCode(), row.machineName(), row.groupId(),
                    row.groupUsername(), row.score(), row.playerBalance(), row.totalRemaining(),
                    row.totalFlow(), row.totalSingleFlow(),
                    row.totalDoubleFlow(),
                    row.totalProfit(), row.totalFanShui(), row.rebateDouble(), row.totalUp(), row.totalDown(),
                    row.rebateMode(), row.effectiveRebate());
        }
    }
}


