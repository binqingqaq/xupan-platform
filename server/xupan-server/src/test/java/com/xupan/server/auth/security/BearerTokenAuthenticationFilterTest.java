package com.xupan.server.auth.security;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.SessionRecord;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.service.TokenService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BearerTokenAuthenticationFilterTest {

    private final TokenService tokenService = mock(TokenService.class);
    private final AuthenticatedUserDetailsService detailsService = mock(AuthenticatedUserDetailsService.class);
    private final BearerTokenAuthenticationFilter filter =
            new BearerTokenAuthenticationFilter(tokenService, detailsService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAndMalformedAuthorizationHeadersRemainAnonymous() throws Exception {
        assertAnonymous(null);
        assertAnonymous("Basic abc");
        assertAnonymous("Bearer");
        assertAnonymous("Bearer ");
        assertAnonymous("Bearer first second");
        verifyNoInteractions(tokenService, detailsService);
    }

    @Test
    void validBearerTokenLoadsCurrentServerPrincipalAndAuthorities() throws Exception {
        SessionRecord session = new SessionRecord(1L, "session", 7L, "access-hash",
                Instant.now().plusSeconds(60), "refresh-hash", Instant.now().plusSeconds(3600),
                null, null, null, null, null, Instant.now(), 0L);
        AuthenticatedUser user = new AuthenticatedUser(
                new UserAccount(7L, "alice", "Alice", null, "password-hash", "ACTIVE", 0,
                        null, 0L, null, null),
                List.of(() -> "PERM_CHAT_ROOM_READ"));
        when(tokenService.validateAccessToken("access-token")).thenReturn(Optional.of(session));
        when(detailsService.loadUserById(7L)).thenReturn(user);

        MockHttpServletRequest request = request("Bearer access-token");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(user);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(value -> value.getAuthority()).containsExactly("PERM_CHAT_ROOM_READ");
        verify(tokenService).validateAccessToken("access-token");
        verify(detailsService).loadUserById(7L);
    }

    @Test
    void expiredRevokedOrStatusInvalidTokenIsLeftToSecurityChain() throws Exception {
        SessionRecord session = new SessionRecord(1L, "session", 7L, "access-hash",
                Instant.now().plusSeconds(60), "refresh-hash", Instant.now().plusSeconds(3600),
                null, null, null, null, null, Instant.now(), 0L);
        when(tokenService.validateAccessToken(anyString())).thenReturn(Optional.empty());
        assertAnonymous("Bearer expired-token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        AuthenticatedUser disabled = new AuthenticatedUser(
                new UserAccount(7L, "alice", "Alice", null, "password-hash", "DISABLED", 0,
                        null, 0L, null, null), List.of());
        when(tokenService.validateAccessToken("disabled-token")).thenReturn(Optional.of(session));
        when(detailsService.loadUserById(7L)).thenReturn(disabled);
        assertAnonymous("Bearer disabled-token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void assertAnonymous(String authorization) throws ServletException, java.io.IOException {
        filter.doFilter(request(authorization), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
