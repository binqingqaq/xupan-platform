package com.xupan.server.agent.web;

import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.agent.service.AgentConsoleService;
import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record AgentOverviewResponse(long id, String code, String displayName, String groupCode,
                                        String groupDisplayName, String status, long normalCount, long botCount,
                                        BigDecimal totalBalance, Instant createdAt) {
        static AgentOverviewResponse from(AgentRepository.AgentRow row) {
            return new AgentOverviewResponse(row.id(), row.code(), row.displayName(), row.groupCode(),
                    row.groupDisplayName(), row.status(), row.normalCount(), row.botCount(),
                    row.totalBalance(), row.createdAt());
        }
    }

    public record AgentPlayerPageResponse(List<AgentPlayerResponse> items, int page, int pageSize, long total) {
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
}
