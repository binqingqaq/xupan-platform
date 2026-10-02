package com.xupan.server.agent.web;

import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.agent.service.AgentConsoleService;
import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.playerauth.service.PlayerLinkAuthenticationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentConsoleController {

    private final AgentConsoleService service;

    public AgentConsoleController(AgentConsoleService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public AgentOverviewResponse me(Authentication authentication) {
        return AgentOverviewResponse.from(service.overview(principal(authentication).getUserId()));
    }

    @GetMapping("/players")
    public AgentPlayerPageResponse players(Authentication authentication,
                                           @RequestParam(required = false) String kind,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int pageSize) {
        AgentConsoleService.AgentPlayerPage result = service.listPlayers(
                principal(authentication).getUserId(), kind, keyword, page, pageSize);
        return new AgentPlayerPageResponse(result.items().stream().map(AgentPlayerResponse::from).toList(),
                result.page(), result.pageSize(), result.total());
    }

    @GetMapping("/operations")
    public OperationsResponse operations(Authentication authentication,
                                         @RequestParam(required = false) String day) {
        return OperationsResponse.from(service.operations(principal(authentication).getUserId(), day));
    }

    @PostMapping("/players/normal")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentPlayerResponse createNormal(Authentication authentication,
                                            @Valid @RequestBody CreateNormalRequest request) {
        return AgentPlayerResponse.from(service.createNormal(
                principal(authentication).getUserId(), request.displayName()));
    }

    @PostMapping("/players/bot")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentPlayerResponse createBot(Authentication authentication,
                                         @Valid @RequestBody CreateBotRequest request) {
        return AgentPlayerResponse.from(service.createBot(
                principal(authentication).getUserId(), request.userCode(), request.displayName()));
    }

    @PostMapping("/players/{userId}/score")
    @ResponseStatus(HttpStatus.OK)
    public ScoreChangeResponse changeScore(Authentication authentication, @PathVariable long userId,
                                           @Valid @RequestBody ScoreChangeRequest request) {
        return ScoreChangeResponse.from(service.changeScore(principal(authentication).getUserId(), userId,
                request.direction(), request.amount(), request.idempotencyKey()));
    }

    @GetMapping("/players/{userId}/link")
    public LinkResponse currentLink(Authentication authentication, @PathVariable long userId,
                                    HttpServletRequest request) {
        return LinkResponse.from(service.currentLink(principal(authentication).getUserId(), userId), origin(request));
    }

    @PostMapping("/players/{userId}/link/rotate")
    public LinkResponse rotateLink(Authentication authentication, @PathVariable long userId,
                                   HttpServletRequest request) {
        return LinkResponse.from(service.rotateLink(principal(authentication).getUserId(), userId), origin(request));
    }

    @PostMapping("/players/{userId}/link/{linkId}/revoke")
    public void revokeLink(Authentication authentication, @PathVariable long userId, @PathVariable long linkId) {
        service.revokeLink(principal(authentication).getUserId(), userId, linkId);
    }

    @PostMapping("/players/{userId}/link/{linkId}/restore")
    public void restoreLink(Authentication authentication, @PathVariable long userId, @PathVariable long linkId) {
        service.restoreLink(principal(authentication).getUserId(), userId, linkId);
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record AgentOverviewResponse(long id, String code, String displayName, BigDecimal score, String groupCode,
                                        String groupDisplayName, String status, long normalCount, long botCount,
                                        BigDecimal totalBalance, Instant createdAt) {
        static AgentOverviewResponse from(AgentRepository.AgentRow row) {
            return new AgentOverviewResponse(row.id(), row.code(), row.displayName(), row.score(), row.groupCode(),
                    row.groupDisplayName(), row.status(), row.normalCount(), row.botCount(),
                    row.totalBalance(), row.createdAt());
        }
    }

    public record AgentPlayerPageResponse(List<AgentPlayerResponse> items, int page, int pageSize, long total) {
    }

    public record OperationsResponse(String day, long betCount, BigDecimal turnover, BigDecimal netProfit,
                                     long pendingBetCount, BigDecimal normalTurnover, BigDecimal botTurnover,
                                     long activePlayerCount) {
        static OperationsResponse from(AgentConsoleService.OperationsSummary summary) {
            return new OperationsResponse(summary.day(), summary.betCount(), summary.turnover(),
                    summary.netProfit(), summary.pendingBetCount(), summary.normalTurnover(),
                    summary.botTurnover(), summary.activePlayerCount());
        }
    }

    public record AgentPlayerResponse(long userId, long accountId, String internalCode, String displayName,
                                      String memberCode, String playerKind, String userStatus,
                                      String accountStatus, BigDecimal balance, Instant createdAt,
                                      Instant lastLoginAt) {
        static AgentPlayerResponse from(AgentRepository.AgentPlayerRow row) {
            return new AgentPlayerResponse(row.userId(), row.accountId(), row.internalCode(), row.displayName(),
                    row.memberCode(), row.playerKind(), row.userStatus(), row.accountStatus(), row.balance(),
                    row.createdAt(), row.lastLoginAt());
        }
    }

    public record CreateNormalRequest(@NotBlank String displayName) {
    }

    public record CreateBotRequest(String userCode, @NotBlank String displayName) {
    }

    public record ScoreChangeRequest(@NotBlank String direction,
                                     @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
                                     @NotBlank String idempotencyKey) {
    }

    public record ScoreChangeResponse(String direction, BigDecimal amount, BigDecimal agentScore,
                                      BigDecimal playerBalance, long ledgerId, boolean replay) {
        static ScoreChangeResponse from(AgentConsoleService.ScoreChange change) {
            return new ScoreChangeResponse(change.direction(), change.amount(), change.agentScore(),
                    change.playerBalance(), change.ledgerId(), change.replay());
        }
    }

    public record LinkResponse(long linkId, long userId, String scope, Instant expiresAt, String accessUrl) {
        static LinkResponse from(PlayerLinkAuthenticationService.IssuedLink link, String origin) {
            String token = URLEncoder.encode(link.rawToken(), StandardCharsets.UTF_8);
            return new LinkResponse(link.linkId(), link.userId(), link.scope(), link.expiresAt(),
                    origin + "/33/" + token);
        }
    }

    private static String origin(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean standard = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (standard ? "" : ":" + port);
    }
}
