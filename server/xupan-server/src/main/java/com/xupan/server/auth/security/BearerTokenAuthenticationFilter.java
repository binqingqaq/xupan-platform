package com.xupan.server.auth.security;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.SessionRecord;
import com.xupan.server.auth.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Converts a valid opaque Bearer token into a server-loaded authenticated principal. */
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final AuthenticatedUserDetailsService userDetailsService;

    public BearerTokenAuthenticationFilter(TokenService tokenService,
                                           AuthenticatedUserDetailsService userDetailsService) {
        this.tokenService = tokenService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<String> rawToken = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
            Optional<SessionRecord> session = rawToken.flatMap(tokenService::validateAccessToken);
            if (session.isEmpty() && "/api/auth/logout".equals(request.getRequestURI())) {
                session = rawToken.flatMap(tokenService::findSessionForLogout);
            }
            Optional<AuthenticatedSession> authenticatedSession = session.flatMap(value -> {
                Optional<AuthenticatedUser> user = loadEnabledUser(value);
                if (user.isEmpty() && "/api/auth/logout".equals(request.getRequestURI())) {
                    user = loadUser(value);
                }
                return user.map(current -> new AuthenticatedSession(value.sessionId(), current));
            });
            authenticatedSession.ifPresent(value -> authenticate(request, value));
        }
        filterChain.doFilter(request, response);
    }

    private Optional<AuthenticatedUser> loadEnabledUser(SessionRecord session) {
        try {
            AuthenticatedUser user = userDetailsService.loadUserById(session.userId());
            if (session.isChatOnly()) {
                user = user.asChatOnly();
            } else if (!Objects.equals(session.authMode(), user.authMode())
                    || !Objects.equals(session.scope(), user.scope())) {
                user = new AuthenticatedUser(user.account(), user.getAuthorities(),
                        session.authMode(), session.scope());
            }
            return user.isEnabled() && user.isAccountNonLocked() ? Optional.of(user) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<AuthenticatedUser> loadUser(SessionRecord session) {
        try {
            return Optional.of(userDetailsService.loadUserById(session.userId()));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static void authenticate(HttpServletRequest request, AuthenticatedSession session) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(session.user(), null, session.user().getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(com.xupan.server.auth.service.AuthenticationService.SESSION_ID_ATTRIBUTE,
                session.sessionId());
    }

    private static Optional<String> bearerToken(String header) {
        if (header == null || header.isEmpty()) {
            return Optional.empty();
        }
        int separator = header.indexOf(' ');
        if (separator <= 0 || separator != header.lastIndexOf(' ')
                || !"Bearer".equalsIgnoreCase(header.substring(0, separator))) {
            return Optional.empty();
        }
        String token = header.substring(separator + 1);
        if (token.isBlank() || token.codePoints().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    private record AuthenticatedSession(String sessionId, AuthenticatedUser user) {
    }
}
