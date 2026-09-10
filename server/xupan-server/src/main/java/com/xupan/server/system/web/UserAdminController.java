package com.xupan.server.system.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.system.service.UserAdminService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    public List<UserSummary> list(@RequestParam(defaultValue = "ACTIVE") String status) {
        return userAdminService.listUsers(status).stream().map(UserSummary::from).toList();
    }

    @PostMapping
    public UserSummary create(Authentication authentication,
                              @Valid @RequestBody CreateUserRequest request) {
        AuthenticatedUser operator = principal(authentication);
        long userId = userAdminService.createUser(request.username(), request.displayName(),
                request.rawPassword(), "USER", operator.getUserId());
        return UserSummary.from(userAdminService.listUsers(null).stream()
                .filter(user -> user.id() == userId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("创建用户后未找到用户")));
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record UserSummary(long id, String username, String displayName, String status) {
        static UserSummary from(UserAccount user) {
            return new UserSummary(user.id(), user.username(), user.displayName(), user.status());
        }
    }
}
