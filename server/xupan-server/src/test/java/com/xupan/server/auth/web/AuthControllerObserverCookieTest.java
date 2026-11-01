package com.xupan.server.auth.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerObserverCookieTest {

    @Test
    void writesAnObserverRefreshCookieSeparateFromPlayerAndPlatformCookies() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        AuthController.writeObserverRefreshCookie(response, "observer-refresh", false);

        assertThat(response.getCookie(AuthController.OBSERVER_REFRESH_COOKIE))
                .satisfies(cookie -> {
                    assertThat(cookie.getValue()).isEqualTo("observer-refresh");
                    assertThat(cookie.isHttpOnly()).isTrue();
                    assertThat(cookie.getPath()).isEqualTo("/api/auth");
                });
        assertThat(response.getCookie(AuthController.PLAYER_REFRESH_COOKIE)).isNull();
        assertThat(response.getCookie(AuthController.REFRESH_COOKIE)).isNull();
    }
}
