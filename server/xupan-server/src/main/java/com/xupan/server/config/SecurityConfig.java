package com.xupan.server.config;

import com.xupan.server.auth.security.AuthenticatedUserDetailsService;
import com.xupan.server.auth.security.BearerTokenAuthenticationFilter;
import com.xupan.server.auth.web.AuthenticationExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.http.HttpMethod;

@Configuration
public class SecurityConfig {

    private final BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter;
    private final AuthenticationExceptionHandler exceptionHandler;

    public SecurityConfig(com.xupan.server.auth.service.TokenService tokenService,
                          AuthenticatedUserDetailsService userDetailsService,
                          AuthenticationExceptionHandler exceptionHandler) {
        this.bearerTokenAuthenticationFilter = new BearerTokenAuthenticationFilter(tokenService, userDetailsService);
        this.exceptionHandler = exceptionHandler;
    }

    @Bean
    SecurityFilterChain demoSecurity(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(exceptionHandler)
                        .accessDeniedHandler(exceptionHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/login", "/player-login", "/forbidden", "/room", "/admin", "/admin/users", "/admin/test-players", "/admin/robots",
                                "/display", "/display/", "/display/**", "/assets/**", "/favicon.ico").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/player-auth/exchange", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/media/avatars/**").permitAll()
                        .requestMatchers("/actuator/info", "/actuator/metrics", "/actuator/metrics/**")
                        .hasAuthority("PERM_SYSTEM_MONITOR_READ")
                        // WebSocket authentication is performed by the one-time ticket interceptor.
                        .requestMatchers("/ws/chat/**").permitAll()
                        .requestMatchers("/api/auth/logout", "/api/auth/me", "/api/auth/ws-ticket").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/me/avatar").access(nonChatOnly())
                        .requestMatchers("/api/me/wallet").hasAuthority("PERM_WALLET_READ")
                        .requestMatchers("/api/admin/users/*/wallet/grants").hasAuthority("PERM_WALLET_GRANT")
                        .requestMatchers("/api/admin/users/*/wallet/adjustments").hasAuthority("PERM_WALLET_ADJUST")
                        .requestMatchers("/api/admin/users/*/wallet", "/api/admin/users/*/wallet/ledger")
                        .hasAuthority("PERM_WALLET_LEDGER_READ")
                        .requestMatchers("/api/admin/users", "/api/admin/users/**").hasAuthority("PERM_USER_MANAGE")
                        .requestMatchers("/api/admin/test-players", "/api/admin/test-players/**")
                        .hasAuthority("PERM_USER_MANAGE")
                        .requestMatchers("/api/admin/player-desk", "/api/admin/player-desk/**")
                        .hasAuthority("PERM_USER_MANAGE")
                        .requestMatchers("/api/admin/roles").hasAuthority("PERM_USER_MANAGE")
                        .requestMatchers(HttpMethod.GET, "/api/chat/rooms/*", "/api/chat/rooms/*/messages")
                        .hasAnyAuthority("PERM_CHAT_ROOM_READ", "SCOPE_CHAT_ONLY")
                        .requestMatchers(HttpMethod.POST, "/api/chat/rooms/*/messages")
                        .hasAnyAuthority("PERM_CHAT_MESSAGE_SEND", "SCOPE_CHAT_ONLY")
                        .requestMatchers(HttpMethod.POST, "/api/chat/rooms/*/read-cursor")
                        .hasAnyAuthority("PERM_CHAT_ROOM_READ", "SCOPE_CHAT_ONLY")
                        .requestMatchers("/api/demo/game/current", "/api/demo/game/bets/summary").hasAuthority("PERM_GAME_CURRENT_READ")
                        .requestMatchers("/api/demo/game/bets").hasAuthority("PERM_GAME_BET_PLACE")
                        .requestMatchers("/api/demo/account").hasAuthority("PERM_GAME_CURRENT_READ")
                        .requestMatchers("/api/demo/game/admin/**").hasAuthority("PERM_GAME_ODDS_WRITE")
                        .requestMatchers("/api/demo/admin/**").hasAuthority("PERM_USER_MANAGE")
                        .anyRequest().access(nonChatOnly()))
                .addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static AuthorizationManager<RequestAuthorizationContext> nonChatOnly() {
        return (authentication, context) -> {
            org.springframework.security.core.Authentication current = authentication.get();
            boolean allowed = current != null
                    && current.isAuthenticated()
                    && current.getAuthorities().stream()
                    .noneMatch(authority -> "SCOPE_CHAT_ONLY".equals(authority.getAuthority()));
            return new AuthorizationDecision(allowed);
        };
    }
}
