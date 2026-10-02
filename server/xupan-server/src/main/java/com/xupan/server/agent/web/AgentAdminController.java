package com.xupan.server.agent.web;

import com.xupan.server.agent.domain.AgentGroup;
import com.xupan.server.agent.repository.AgentRepository;
import com.xupan.server.agent.service.AgentAdminService;
import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/api/admin")
public class AgentAdminController {

    private final AgentAdminService service;

    public AgentAdminController(AgentAdminService service) {
        this.service = service;
    }

    @GetMapping("/agent-groups")
    public List<AgentGroupResponse> groups(Authentication authentication) {
        return service.listGroups(principal(authentication).getUserId()).stream()
                .map(AgentGroupResponse::from).toList();
    }

    @PostMapping("/agent-groups")
    public AgentGroupResponse createGroup(Authentication authentication,
                                          @Valid @RequestBody CreateGroupRequest request) {
        return AgentGroupResponse.from(service.createGroup(request.code(), request.displayName(),
                principal(authentication).getUserId()));
    }

    @PatchMapping("/agent-groups/{groupId}/status")
    public AgentGroupResponse changeGroupStatus(Authentication authentication, @PathVariable long groupId,
                                                @Valid @RequestBody StatusRequest request) {
        return AgentGroupResponse.from(service.changeGroupStatus(groupId, request.status(),
                principal(authentication).getUserId()));
    }

    @GetMapping("/agents")
    public AgentPageResponse agents(Authentication authentication,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Long groupId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int pageSize) {
        AgentAdminService.AgentPage result = service.listAgents(status, keyword, groupId, page, pageSize,
                principal(authentication).getUserId());
        return new AgentPageResponse(result.items().stream().map(AgentResponse::from).toList(),
                result.page(), result.pageSize(), result.total());
    }

    @PostMapping("/agents")
    public AgentResponse createAgent(Authentication authentication,
                                     @Valid @RequestBody CreateAgentRequest request) {
        return AgentResponse.from(service.createAgent(request.agentCode(), request.username(),
                request.displayName(), request.rawPassword(), request.groupId(),
                principal(authentication).getUserId()));
    }

    @PatchMapping("/agents/{agentId}/status")
    public AgentResponse changeAgentStatus(Authentication authentication, @PathVariable long agentId,
                                           @Valid @RequestBody StatusRequest request) {
        return AgentResponse.from(service.changeAgentStatus(agentId, request.status(),
                principal(authentication).getUserId()));
    }

    @GetMapping("/agent-player-assignments")
    public AgentAssignmentPageResponse playerAssignments(Authentication authentication,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(required = false) Long agentId,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int pageSize) {
        AgentAdminService.AgentPlayerPage result = service.listPlayerAssignments(keyword, agentId, page, pageSize,
                principal(authentication).getUserId());
        return new AgentAssignmentPageResponse(result.items().stream().map(AgentAssignmentResponse::from).toList(),
                result.page(), result.pageSize(), result.total());
    }

    @PutMapping("/agent-player-assignments/{userId}")
    public void assignPlayer(Authentication authentication, @PathVariable long userId,
                             @Valid @RequestBody AssignPlayerRequest request) {
        service.assignPlatformDirectPlayer(userId, request.agentId(), principal(authentication).getUserId());
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record CreateGroupRequest(@NotBlank String code, @NotBlank String displayName) {
    }

    public record CreateAgentRequest(@NotBlank String agentCode, @NotBlank String username,
                                     @NotBlank String displayName, @NotBlank String rawPassword,
                                     Long groupId) {
    }

    public record StatusRequest(@NotBlank String status) {
    }

    public record AssignPlayerRequest(@NotNull Long agentId) {
    }

    public record AgentGroupResponse(long id, String code, String displayName, String status,
                                     Instant createdAt, Instant updatedAt) {
        static AgentGroupResponse from(AgentGroup group) {
            return new AgentGroupResponse(group.id(), group.code(), group.displayName(), group.status(),
                    group.createdAt(), group.updatedAt());
        }
    }

    public record AgentPageResponse(List<AgentResponse> items, int page, int pageSize, long total) {
    }

    public record AgentResponse(long id, String code, String displayName, BigDecimal score, Long groupId, String groupCode,
                                String groupDisplayName, Long accountUserId, String accountUsername,
                                boolean systemOwned, String status, long normalCount, long botCount,
                                BigDecimal totalBalance, Instant createdAt, Instant updatedAt) {
        static AgentResponse from(AgentRepository.AgentRow row) {
            return new AgentResponse(row.id(), row.code(), row.displayName(), row.score(), row.groupId(), row.groupCode(),
                    row.groupDisplayName(), row.accountUserId(), row.accountUsername(), row.systemOwned(),
                    row.status(), row.normalCount(), row.botCount(), row.totalBalance(),
                    row.createdAt(), row.updatedAt());
        }
    }

    public record AgentAssignmentPageResponse(List<AgentAssignmentResponse> items, int page, int pageSize,
                                              long total) {
    }

    public record AgentAssignmentResponse(long userId, String displayName, String memberCode, String playerKind,
                                          String userStatus, String accountStatus, BigDecimal balance,
                                          long agentId, String agentCode, String agentName, boolean systemOwned) {
        static AgentAssignmentResponse from(AgentRepository.PlayerAssignmentRow row) {
            return new AgentAssignmentResponse(row.userId(), row.displayName(), row.memberCode(), row.playerKind(),
                    row.userStatus(), row.accountStatus(), row.balance(), row.agentId(), row.agentCode(),
                    row.agentName(), row.systemOwned());
        }
    }
}
