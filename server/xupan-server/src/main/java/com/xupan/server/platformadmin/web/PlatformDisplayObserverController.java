package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.displayobserver.DisplayObserverBindingRepository;
import com.xupan.server.displayobserver.DisplayObserverBindingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/settings/display-observer")
public class PlatformDisplayObserverController {

    private final DisplayObserverBindingService service;

    public PlatformDisplayObserverController(DisplayObserverBindingService service) {
        this.service = service;
    }

    @GetMapping
    public BindingResponse get(Authentication authentication) {
        return BindingResponse.from(service.getBinding(operator(authentication).getUserId()));
    }

    @GetMapping("/machines")
    public List<MachineOption> machines(Authentication authentication) {
        return service.eligibleMachines(operator(authentication).getUserId()).stream()
                .map(MachineOption::from).toList();
    }

    @GetMapping("/machines/{agentId}/bots")
    public List<BotOption> bots(Authentication authentication, @PathVariable long agentId) {
        return service.eligibleBots(agentId, operator(authentication).getUserId()).stream()
                .map(BotOption::from).toList();
    }

    @PutMapping
    public BindingResponse bind(Authentication authentication, @Valid @RequestBody BindRequest body) {
        return BindingResponse.from(service.bind(body.agentId(), body.viewerUserId(), body.expectedVersion(),
                operator(authentication).getUserId()));
    }

    @PostMapping("/enabled")
    public BindingResponse setEnabled(Authentication authentication, @Valid @RequestBody EnabledRequest body) {
        return BindingResponse.from(service.setEnabled(body.enabled(), body.expectedVersion(), body.reason(),
                operator(authentication).getUserId()));
    }

    @PostMapping("/rotate")
    public BindingResponse rotate(Authentication authentication, @Valid @RequestBody ChangeRequest body) {
        return BindingResponse.from(service.rotate(body.reason(), body.expectedVersion(),
                operator(authentication).getUserId()));
    }

    @PostMapping("/reset-binding")
    public BindingResponse resetBinding(Authentication authentication, @Valid @RequestBody ResetBindingRequest body) {
        return BindingResponse.from(service.resetBinding(body.agentId(), body.viewerUserId(),
                body.expectedVersion(), body.reason(), operator(authentication).getUserId()));
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record BindRequest(@Positive long agentId, @Positive long viewerUserId,
                              @PositiveOrZero long expectedVersion) {
    }

    public record EnabledRequest(boolean enabled, @PositiveOrZero long expectedVersion, @NotBlank String reason) {
    }

    public record ChangeRequest(@PositiveOrZero long expectedVersion, @NotBlank String reason) {
    }

    public record ResetBindingRequest(@Positive long agentId, @Positive long viewerUserId,
                                      @PositiveOrZero long expectedVersion, @NotBlank String reason) {
    }

    public record MachineOption(long id, String username, String displayName, String groupDisplayName) {
        private static MachineOption from(DisplayObserverBindingRepository.MachineCandidate candidate) {
            return new MachineOption(candidate.id(), candidate.username(),
                    candidate.displayName(), candidate.groupDisplayName());
        }
    }

    public record BotOption(long userId, String displayName, String memberCode) {
        private static BotOption from(DisplayObserverBindingRepository.ViewerCandidate candidate) {
            return new BotOption(candidate.userId(), candidate.displayName(), candidate.memberCode());
        }
    }

    public record BindingResponse(boolean configured, boolean enabled, boolean valid, long version,
                                  Long agentId, String machineUsername, String machineDisplayName,
                                  String groupDisplayName, Long viewerUserId, String viewerDisplayName,
                                  String publicId, String publicPath, String invalidReason) {
        private static BindingResponse from(DisplayObserverBindingService.BindingView view) {
            String publicPath = view.configured() && view.enabled() && view.valid() && view.publicId() != null
                    ? "/display/observe/" + view.publicId() : null;
            return new BindingResponse(view.configured(), view.enabled(), view.valid(), view.version(),
                    view.agentId(), view.machineUsername(), view.machineDisplayName(), view.groupDisplayName(),
                    view.viewerUserId(), view.viewerDisplayName(), view.publicId(), publicPath, view.invalidReason());
        }
    }
}
