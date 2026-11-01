package com.xupan.server.config;

import com.xupan.server.displayobserver.DisplayObserverRateLimitFilter;
import com.xupan.server.displayobserver.DisplayObserverRateLimiter;
import com.xupan.server.auth.security.BearerTokenAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SecurityConfigTest {

    @Test
    void hby019SourceDocumentsAllowSameOriginFraming() throws Exception {
        var writer = SecurityConfig.frameOptionsWriter();
        var panelRequest = request("/hby019-demo/source/account-panel.html");
        var panelResponse = new MockHttpServletResponse();

        writer.writeHeaders(panelRequest, panelResponse);

        assertEquals("SAMEORIGIN", panelResponse.getHeader("X-Frame-Options"));

        var consoleResponse = new MockHttpServletResponse();
        writer.writeHeaders(request("/hby019-demo/source/console.html"), consoleResponse);
        assertEquals("SAMEORIGIN", consoleResponse.getHeader("X-Frame-Options"));

        var nestedAssetResponse = new MockHttpServletResponse();
        writer.writeHeaders(request("/hby019-demo/source/static/xz/admin/main.css"), nestedAssetResponse);
        assertEquals("SAMEORIGIN", nestedAssetResponse.getHeader("X-Frame-Options"));

        var pageResponse = new MockHttpServletResponse();
        writer.writeHeaders(request("/room"), pageResponse);
        assertEquals("DENY", pageResponse.getHeader("X-Frame-Options"));

        var nestedPathResponse = new MockHttpServletResponse();
        writer.writeHeaders(request("/hby019-demo/sourceevil/account-panel.html"), nestedPathResponse);
        assertEquals("DENY", nestedPathResponse.getHeader("X-Frame-Options"));
    }

    @Test
    void observerRateLimitFilterIsOnlyRegisteredInsideTheSecurityChain() {
        DisplayObserverRateLimitFilter filter = new DisplayObserverRateLimitFilter(
                new DisplayObserverRateLimiter(java.time.Duration.ofMinutes(1), 60, 600, 600, 3000));
        FilterRegistrationBean<DisplayObserverRateLimitFilter> registration =
                DisplayObserverRateLimitConfiguration.disableObserverRateLimitServletRegistration(filter);

        org.assertj.core.api.Assertions.assertThat(registration.isEnabled()).isFalse();
        org.assertj.core.api.Assertions.assertThat(registration.getFilter()).isSameAs(filter);

        HttpSecurity http = mock(HttpSecurity.class);
        SecurityConfig.installObserverRateLimitFilter(http, filter);
        verify(http).addFilterAfter(filter, BearerTokenAuthenticationFilter.class);
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest();
        request.setRequestURI(path);
        request.setServletPath(path);
        return request;
    }
}
