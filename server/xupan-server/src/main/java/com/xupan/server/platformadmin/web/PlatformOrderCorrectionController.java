package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformOrderCorrectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/order-corrections")
public class PlatformOrderCorrectionController {

    private final PlatformOrderCorrectionService service;

    public PlatformOrderCorrectionController(PlatformOrderCorrectionService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse list(Authentication authentication,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "30") int pageSize) {
        return PageResponse.from(service.list(page, pageSize, operator(authentication).getUserId()));
    }

    @GetMapping("/{id}")
    public DetailResponse detail(Authentication authentication, @PathVariable long id) {
        return DetailResponse.from(service.detail(id, operator(authentication).getUserId()));
    }

    @PostMapping("/{id}")
    public CorrectionResponse correct(Authentication authentication, @PathVariable long id,
                                      @Valid @RequestBody CorrectionRequest request) {
        return CorrectionResponse.from(service.correct(id, request.command(), request.ballNumber(),
                request.idempotencyKey(), operator(authentication).getUserId()));
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record CorrectionRequest(@NotBlank String command, int ballNumber,
                                    @NotBlank String idempotencyKey) {
    }

    public record PageResponse(List<RowResponse> items, int page, int pageSize, long total) {
        static PageResponse from(PlatformOrderCorrectionService.Page page) {
            return new PageResponse(page.items().stream().map(RowResponse::from).toList(),
                    page.page(), page.pageSize(), page.total());
        }
    }

    public record RowResponse(long id, String machineName, String playerName, String issueNumber,
                              Instant createdAt, BigDecimal stake, String command) {
        static RowResponse from(PlatformOrderCorrectionService.Row row) {
            return new RowResponse(row.id(), row.machineName(), row.playerName(), row.issueNumber(),
                    row.createdAt(), row.stake(), row.command());
        }
    }

    public record DetailResponse(long id, String issueNumber, int ballNumber, String machineName,
                                 String playerName, String command, BigDecimal stake,
                                 String settlementStatus, int editVersion) {
        static DetailResponse from(PlatformOrderCorrectionService.Detail detail) {
            return new DetailResponse(detail.id(), detail.issueNumber(), detail.ballNumber(),
                    detail.machineName(), detail.playerName(), detail.command(), detail.stake(),
                    detail.settlementStatus(), detail.editVersion());
        }
    }

    public record CorrectionResponse(long id, String playType, String command, BigDecimal stake,
                                     BigDecimal stakeDelta, BigDecimal walletBalance, Long ledgerId) {
        static CorrectionResponse from(PlatformOrderCorrectionService.CorrectionResult result) {
            return new CorrectionResponse(result.id(), result.playType(), result.command(), result.stake(),
                    result.stakeDelta(), result.walletBalance(), result.ledgerId());
        }
    }
}
