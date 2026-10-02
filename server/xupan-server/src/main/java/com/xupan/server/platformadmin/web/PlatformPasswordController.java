package com.xupan.server.platformadmin.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.platformadmin.service.PlatformPasswordService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/password")
public class PlatformPasswordController {

    private final PlatformPasswordService service;

    public PlatformPasswordController(PlatformPasswordService service) {
        this.service = service;
    }

    @GetMapping
    public PasswordFormResponse get(Authentication authentication) {
        PlatformPasswordService.PasswordForm form = service.get(operator(authentication).getUserId());
        return new PasswordFormResponse(form.username(), form.canChangeUsername());
    }

    @PutMapping
    public ChangeResponse change(Authentication authentication,
                                 @RequestBody ChangeRequest request) {
        PlatformPasswordService.ChangeResult result = service.change(
                new PlatformPasswordService.ChangeInput(request.username(), request.oldPassword(),
                        request.newPassword(), request.confirmPassword()),
                operator(authentication).getUserId());
        return new ChangeResponse(result.message(), result.usernameChanged(),
                result.passwordChanged(), result.username());
    }

    private static AuthenticatedUser operator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record PasswordFormResponse(String username, boolean canChangeUsername) {
    }

    public record ChangeRequest(String username, String oldPassword, String newPassword,
                                String confirmPassword) {
    }

    public record ChangeResponse(String message, boolean usernameChanged, boolean passwordChanged,
                                 String username) {
    }
}
