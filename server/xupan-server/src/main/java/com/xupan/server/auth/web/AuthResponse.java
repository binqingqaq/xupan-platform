package com.xupan.server.auth.web;

import com.xupan.server.auth.service.AuthenticationService;

public record AuthResponse(String accessToken, long expiresIn, CurrentUserResponse user) {

    public static AuthResponse from(AuthenticationService.LoginResult result) {
        return new AuthResponse(result.tokens().accessToken(), result.expiresInSeconds(),
                CurrentUserResponse.from(result.user()));
    }
}
