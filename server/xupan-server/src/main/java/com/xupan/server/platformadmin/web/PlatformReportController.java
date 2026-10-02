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
    public List<ScoreFlowResponse> scoreFlow(Authentication authentication,
                                             @RequestParam(required = false) String day,
                                             @RequestParam(required = false) String day3,
                                             @RequestParam(required = false) Long subAccountId) {
        return service.scoreFlow(day, day3, subAccountId, operator(authentication).getUserId()).stream()
                .map(ScoreFlowResponse::from).toList();
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

    public record ScoreFlowResponse(long id, Instant createdAt, String operationType, BigDecimal amount,
                                    BigDecimal balanceAfter, String reason, String memberCode,
                                    String playerName, long machineId, String machineName, String subAccount) {
        static ScoreFlowResponse from(PlatformReportService.ScoreFlowRow row) {
            return new ScoreFlowResponse(row.id(), row.createdAt(), row.operationType(), row.amount(),
                    row.balanceAfter(), row.reason(), row.memberCode(), row.playerName(), row.machineId(),
                    row.machineName(), row.subAccount());
        }
    }

    public record ProfitReportResponse(String day, String day3, Instant fromInclusive, Instant toExclusive,
                                       List<MachineProfitResponse> machines, BigDecimal totalRemaining,
                                       BigDecimal totalFlow, BigDecimal totalSingleFlow,
                                       BigDecimal totalDoubleFlow, BigDecimal totalProfit,
                                       BigDecimal totalFanShui, BigDecimal totalUp, BigDecimal totalDown,
                                       BigDecimal totalUpDown) {
        static ProfitReportResponse from(PlatformReportService.ProfitReport report) {
            return new ProfitReportResponse(report.day().toString(), report.day3().toString(),
                    report.fromInclusive(), report.toExclusive(),
                    report.machines().stream().map(MachineProfitResponse::from).toList(),
                    report.totalRemaining(), report.totalFlow(), report.totalSingleFlow(), report.totalDoubleFlow(),
                    report.totalProfit(), report.totalFanShui(), report.totalUp(), report.totalDown(),
                    report.totalUpDown());
        }
    }

    public record MachineProfitResponse(long machineId, String machineCode, String machineName, Long groupId,
                                        String groupUsername, BigDecimal score, BigDecimal playerBalance,
                                        BigDecimal totalFlow, BigDecimal totalSingleFlow, BigDecimal totalDoubleFlow, BigDecimal totalProfit,
                                        BigDecimal totalFanShui, BigDecimal totalUp, BigDecimal totalDown) {
        static MachineProfitResponse from(PlatformReportService.MachineProfitRow row) {
            return new MachineProfitResponse(row.machineId(), row.machineCode(), row.machineName(), row.groupId(),
                    row.groupUsername(), row.score(), row.playerBalance(), row.totalFlow(), row.totalSingleFlow(),
                    row.totalDoubleFlow(),
                    row.totalProfit(), row.totalFanShui(), row.totalUp(), row.totalDown());
        }
    }
}


