package com.xupan.server.system.web;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.system.service.UserAdminService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/admin")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping("/users")
    public UserPageResponse list(Authentication authentication,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int pageSize) {
        principal(authentication);
        return UserPageResponse.from(userAdminService.listUsers(status, keyword, page, pageSize));
    }

    @GetMapping("/users/{userId}")
    public UserDetailResponse detail(Authentication authentication, @PathVariable long userId) {
        AuthenticatedUser operator = principal(authentication);
        return UserDetailResponse.from(userAdminService.getUser(userId, operator.getUserId()));
    }

    @GetMapping("/roles")
    public java.util.List<RoleOptionResponse> roles(Authentication authentication) {
        AuthenticatedUser operator = principal(authentication);
        return userAdminService.listRoles(operator.getUserId()).stream()
                .map(RoleOptionResponse::from).toList();
    }

    @PostMapping("/users")
    public UserDetailResponse create(Authentication authentication,
                                     @Valid @RequestBody CreateUserRequest request) {
        AuthenticatedUser operator = principal(authentication);
        long userId = userAdminService.createUser(request.username(), request.displayName(),
                request.rawPassword(), operator.getUserId());
        return UserDetailResponse.from(userAdminService.getUser(userId, operator.getUserId()));
    }

    @PatchMapping("/users/{userId}/status")
    public UserDetailResponse changeStatus(Authentication authentication, @PathVariable long userId,
                                            @Valid @RequestBody ChangeUserStatusRequest request) {
        AuthenticatedUser operator = principal(authentication);
        return UserDetailResponse.from(
                userAdminService.changeStatus(userId, request.status(), operator.getUserId()));
    }

    @PostMapping("/users/{userId}/password")
    public void resetPassword(Authentication authentication, @PathVariable long userId,
                              @Valid @RequestBody ResetUserPasswordRequest request) {
        AuthenticatedUser operator = principal(authentication);
        userAdminService.resetPassword(userId, request.rawPassword(), operator.getUserId());
    }

    @PutMapping("/users/{userId}/roles")
    public UserDetailResponse replaceRoles(Authentication authentication, @PathVariable long userId,
                                           @Valid @RequestBody UpdateUserRolesRequest request) {
        AuthenticatedUser operator = principal(authentication);
        return UserDetailResponse.from(
                userAdminService.replaceRoles(userId, request.roleCodes(), operator.getUserId()));
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }
}
