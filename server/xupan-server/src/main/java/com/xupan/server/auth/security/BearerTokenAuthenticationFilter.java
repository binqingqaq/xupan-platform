package com.xupan.server.auth.security;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.SessionRecord;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.service.TokenService;
import com.xupan.server.displayobserver.DisplayObserverAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Converts a valid opaque Bearer token into a server-loaded authenticated principal. */
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);

    private final TokenService tokenService;
    private final AuthenticatedUserDetailsService userDetailsService;
    private final SessionRepository sessionRepository;
    private final DisplayObserverAccessService displayObserverAccessService;

    public BearerTokenAuthenticationFilter(TokenService tokenService,
                                           AuthenticatedUserDetailsService userDetailsService,
                                           SessionRepository sessionRepository,
                                           DisplayObserverAccessService displayObserverAccessService) {
        this.tokenService = tokenService;
        this.userDetailsService = userDetailsService;
        this.sessionRepository = sessionRepository;
        this.displayObserverAccessService = displayObserverAccessService;
    }

    public BearerTokenAuthenticationFilter(TokenService tokenService,
                                           AuthenticatedUserDetailsService userDetailsService,
                                           SessionRepository sessionRepository) {
        this(tokenService, userDetailsService, sessionRepository, null);
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
            authenticatedSession.ifPresent(value -> {
                authenticate(request, value);
                touch(value.sessionId());
            });
        }
        filterChain.doFilter(request, response);
    }

    private void touch(String sessionId) {
        try {
            Instant now = Instant.now();
            sessionRepository.touchIfOlderThan(sessionId, now, now.minusSeconds(10));
        } catch (RuntimeException exception) {
            log.warn("更新会话活跃时间失败 sessionId={}", sessionId, exception);
        }
    }

    private Optional<AuthenticatedUser> loadEnabledUser(SessionRecord session) {
        try {
            AuthenticatedUser user = userDetailsService.loadUserById(session.userId());
            if ("DISPLAY_OBSERVER".equals(session.authMode())) {
                return displayObserverAccessService == null
                        ? Optional.empty()
                        : displayObserverAccessService.principal(user, session.scope());
            }
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
            AuthenticatedUser user = userDetailsService.loadUserById(session.userId());
            if ("DISPLAY_OBSERVER".equals(session.authMode())) {
                return Optional.of(user.asDisplayObserver(session.scope()));
            }
            return Optional.of(user);
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
