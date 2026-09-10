package com.xupan.server.auth.web;

import com.xupan.server.auth.service.AuthenticationService;

import java.util.List;

public record CurrentUserResponse(long id, String username, String displayName,
                                  String avatarKey, List<String> roles, List<String> permissions) {

    public static CurrentUserResponse from(AuthenticationService.CurrentUser user) {
        return new CurrentUserResponse(user.id(), user.username(), user.displayName(), user.avatarKey(),
                user.roles(), user.permissions());
    }
}
