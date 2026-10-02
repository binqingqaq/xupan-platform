package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformOnlinePlayerService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/me/admin-notices")
public class PlayerAdminNoticeController {

    private final PlatformOnlinePlayerService service;

    public PlayerAdminNoticeController(PlatformOnlinePlayerService service) {
        this.service = service;
    }

    @GetMapping("/unread")
    public List<NoticeResponse> unread(Authentication authentication) {
        return service.listUnreadNotices(user(authentication).getUserId()).stream()
                .map(NoticeResponse::from).toList();
    }

    private static AuthenticatedUser user(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record NoticeResponse(long id, String senderName, String title, String content, Instant createdAt) {
        static NoticeResponse from(PlatformOnlinePlayerService.AdminNoticeView notice) {
            return new NoticeResponse(notice.id(), notice.senderName(), notice.title(), notice.content(),
                    notice.createdAt());
        }
    }
}
