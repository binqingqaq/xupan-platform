package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformOnlinePlayerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/online-players")
public class PlatformOnlinePlayerController {

    private final PlatformOnlinePlayerService service;

    public PlatformOnlinePlayerController(PlatformOnlinePlayerService service) {
        this.service = service;
    }

    @GetMapping
    public List<OnlinePlayerResponse> list(Authentication authentication) {
        return service.list(operator(authentication).getUserId()).stream()
                .map(OnlinePlayerResponse::from).toList();
    }

    @PostMapping("/{userId}/disconnect")
    public DisconnectResponse disconnect(Authentication authentication, @PathVariable long userId) {
        return new DisconnectResponse(service.disconnect(userId, operator(authentication).getUserId()));
    }

    @PostMapping("/{userId}/messages")
    public NoticeResponse sendMessage(Authentication authentication, @PathVariable long userId,
                                      @Valid @RequestBody MessageRequest request) {
        return NoticeResponse.from(service.sendMessage(userId, request.title(), request.content(),
                request.idempotencyKey(), operator(authentication).getUserId()));
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record MessageRequest(@NotBlank String title, @NotBlank String content,
                                 @NotBlank String idempotencyKey) {
    }

    public record OnlinePlayerResponse(long userId, String username, String displayName, String userType,
                                       BigDecimal score, String subAccount, String robot, boolean online,
                                       String ip, String city, Instant loginTime) {
        static OnlinePlayerResponse from(PlatformOnlinePlayerService.OnlineRow row) {
            return new OnlinePlayerResponse(row.userId(), row.username(), row.displayName(), row.userType(),
                    row.score(), row.subAccount(), row.robot(), row.online(), row.ip(), row.city(),
                    row.loginTime());
        }
    }

    public record DisconnectResponse(int disconnectedSessions) {
    }

    public record NoticeResponse(long id, String senderName, String title, String content, Instant createdAt) {
        static NoticeResponse from(PlatformOnlinePlayerService.AdminNoticeView notice) {
            return new NoticeResponse(notice.id(), notice.senderName(), notice.title(), notice.content(),
                    notice.createdAt());
        }
    }
}
