package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformDrawHistoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/draw-history")
public class PlatformDrawHistoryController {

    private final PlatformDrawHistoryService service;

    public PlatformDrawHistoryController(PlatformDrawHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse list(Authentication authentication,
                             @RequestParam(defaultValue = "AU8") String gameCode,
                             @RequestParam(required = false) String issueNumber,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "30") int pageSize) {
        return PageResponse.from(service.list(gameCode, issueNumber, page, pageSize,
                operator(authentication).getUserId()));
    }

    @GetMapping("/{issueNumber}/bets")
    public List<BetResponse> bets(Authentication authentication, @PathVariable String issueNumber) {
        return service.bets(issueNumber, operator(authentication).getUserId()).stream()
                .map(BetResponse::from).toList();
    }

    @GetMapping("/{gameCode}/{issueNumber}/bets")
    public List<BetResponse> bets(Authentication authentication, @PathVariable String gameCode,
                                  @PathVariable String issueNumber) {
        return service.bets(gameCode, issueNumber, operator(authentication).getUserId()).stream()
                .map(BetResponse::from).toList();
    }

    @PostMapping("/{issueNumber}/force-settle")
    public SettlementResponse forceSettle(Authentication authentication, @PathVariable String issueNumber) {
        return SettlementResponse.from(service.forceSettleIssue(
                issueNumber, operator(authentication).getUserId()));
    }

    @PostMapping("/{gameCode}/{issueNumber}/force-settle")
    public SettlementResponse forceSettle(Authentication authentication, @PathVariable String gameCode,
                                           @PathVariable String issueNumber) {
        return SettlementResponse.from(service.forceSettleIssue(
                gameCode, issueNumber, operator(authentication).getUserId()));
    }

    @PostMapping("/force-settle-all")
    public SettlementResponse forceSettleAll(Authentication authentication,
                                             @Valid @RequestBody ForceAllRequest request) {
        return SettlementResponse.from(service.forceSettleAll(request.confirm(), request.preview(),
                operator(authentication).getUserId()));
    }

    @PostMapping("/supplement")
    public SupplementResponse supplement(Authentication authentication,
                                         @Valid @RequestBody SupplementRequest request) {
        return SupplementResponse.from(service.supplement(request.gameCode(), request.issueNumber(),
                request.numbers(), request.openedAt(), operator(authentication).getUserId()));
    }

    @PutMapping("/{gameCode}/{issueNumber}/draft")
    public DraftResponse updateDraft(Authentication authentication, @PathVariable String gameCode,
                                     @PathVariable String issueNumber,
                                     @Valid @RequestBody DraftRequest request) {
        return DraftResponse.from(service.updateDraft(gameCode, issueNumber, request.numbers(),
                operator(authentication).getUserId()));
    }

    @PostMapping("/{gameCode}/{issueNumber}/confirm")
    public ConfirmationResponse confirm(Authentication authentication, @PathVariable String gameCode,
                                        @PathVariable String issueNumber) {
        return ConfirmationResponse.from(service.confirmDraft(gameCode, issueNumber,
                operator(authentication).getUserId()));
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record ForceAllRequest(@NotBlank String confirm, boolean preview) {
    }

    public record SupplementRequest(@NotBlank String gameCode, @NotBlank String issueNumber,
                                    @NotNull List<Integer> numbers, Instant openedAt) {
    }

    public record DraftRequest(@NotNull List<Integer> numbers) {
    }

    public record PageResponse(List<HistoryResponse> items, int page, int pageSize, long total) {
        static PageResponse from(PlatformDrawHistoryService.Page page) {
            return new PageResponse(page.items().stream().map(HistoryResponse::from).toList(),
                    page.page(), page.pageSize(), page.total());
        }
    }

    public record HistoryResponse(String gameCode, String gameName, String issueNumber, List<Integer> balls,
                                  String phase, Instant openedAt, Instant settledAt,
                                  Instant drawConfirmedAt, int drawResultVersion,
                                  long betCount, long pendingBetCount, long pendingCenterOrderCount) {
        static HistoryResponse from(PlatformDrawHistoryService.HistoryRow row) {
            return new HistoryResponse(row.gameCode(), row.gameName(), row.issueNumber(), row.balls(),
                    row.phase(), row.openedAt(), row.settledAt(), row.drawConfirmedAt(), row.drawResultVersion(),
                    row.betCount(), row.pendingBetCount(), row.pendingCenterOrderCount());
        }
    }

    public record BetResponse(String betCode, String playerName, String memberCode, String playType,
                              String parametersText, BigDecimal stake, BigDecimal odds, String settlementStatus,
                              BigDecimal netProfit, String explanation, Instant createdAt, Instant settledAt) {
        static BetResponse from(PlatformDrawHistoryService.BetRow row) {
            return new BetResponse(row.betCode(), row.playerName(), row.memberCode(), row.playType(),
                    row.parametersText(), row.stake(), row.odds(), row.settlementStatus(), row.netProfit(),
                    row.explanation(), row.createdAt(), row.settledAt());
        }
    }

    public record SettlementResponse(boolean preview, long histories, long orders, String message) {
        static SettlementResponse from(PlatformDrawHistoryService.SettlementSummary summary) {
            return new SettlementResponse(summary.preview(), summary.histories(), summary.orders(),
                    summary.message());
        }
    }

    public record SupplementResponse(boolean created, int orders, String message) {
        static SupplementResponse from(PlatformDrawHistoryService.SupplementSummary summary) {
            return new SupplementResponse(summary.created(), summary.orders(), summary.message());
        }
    }

    public record DraftResponse(String gameCode, String issueNumber, List<Integer> numbers,
                                int resultVersion, String message) {
        static DraftResponse from(PlatformDrawHistoryService.DraftSummary summary) {
            return new DraftResponse(summary.gameCode(), summary.issueNumber(), summary.numbers(),
                    summary.resultVersion(), summary.message());
        }
    }

    public record ConfirmationResponse(String gameCode, String issueNumber, int resultVersion,
                                       Instant confirmedAt, int localOrders, int centerOrders, String message) {
        static ConfirmationResponse from(PlatformDrawHistoryService.ConfirmationSummary summary) {
            return new ConfirmationResponse(summary.gameCode(), summary.issueNumber(), summary.resultVersion(),
                    summary.confirmedAt(), summary.localOrders(), summary.centerOrders(), summary.message());
        }
    }
}
