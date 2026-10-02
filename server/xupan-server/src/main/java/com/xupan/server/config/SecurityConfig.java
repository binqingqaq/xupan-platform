package com.xupan.server.config;

import com.xupan.server.auth.repository.SessionRepository;
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
                          SessionRepository sessionRepository,
                          AuthenticationExceptionHandler exceptionHandler) {
        this.bearerTokenAuthenticationFilter = new BearerTokenAuthenticationFilter(
                tokenService, userDetailsService, sessionRepository);
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
                        .requestMatchers("/", "/index.html", "/login", "/player-login", "/33", "/33/**", "/forbidden", "/room", "/console", "/console/**", "/agent", "/platform-admin", "/platform-admin/**", "/admin", "/admin/**",
                                "/display", "/display/", "/display/**", "/assets/**", "/avatars/**", "/favicon.ico").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/player-auth/exchange", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/media/avatars/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/display/mobile/home").permitAll()
                        .requestMatchers("/actuator/info", "/actuator/metrics", "/actuator/metrics/**")
                        .hasAuthority("PERM_SYSTEM_MONITOR_READ")
                        // WebSocket authentication is performed by the one-time ticket interceptor.
                        .requestMatchers("/ws/chat/**").permitAll()
                        .requestMatchers("/api/auth/logout", "/api/auth/me", "/api/auth/ws-ticket").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/me/avatar").access(nonChatOnly())
                        .requestMatchers("/api/me/quick-bet-preferences").access(nonChatOnly())
                        .requestMatchers("/api/me/wallet").hasAuthority("PERM_WALLET_READ")
                        .requestMatchers("/api/admin/users/*/wallet/grants").hasAuthority("PERM_WALLET_GRANT")
                        .requestMatchers("/api/admin/users/*/wallet/adjustments").hasAuthority("PERM_WALLET_ADJUST")
                        .requestMatchers("/api/admin/users/*/wallet", "/api/admin/users/*/wallet/ledger")
                        .hasAuthority("PERM_WALLET_LEDGER_READ")
                        .requestMatchers("/api/admin/agent-groups", "/api/admin/agent-groups/**",
                                        "/api/admin/agents", "/api/admin/agents/**",
                                        "/api/admin/agent-player-assignments", "/api/admin/agent-player-assignments/**")
                        .hasAuthority("PERM_AGENT_MANAGE")
                        .requestMatchers("/api/admin/sub-accounts", "/api/admin/sub-accounts/**").hasAuthority("PERM_SUB_ACCOUNT_MANAGE")
                        .requestMatchers("/api/admin/machines", "/api/admin/machines/**").hasAuthority("PERM_MACHINE_MANAGE")
                        .requestMatchers("/api/admin/reports", "/api/admin/reports/**").hasAuthority("PERM_REPORT_READ")
                        .requestMatchers(HttpMethod.GET, "/api/admin/draw-history", "/api/admin/draw-history/**").hasAuthority("PERM_DRAW_HISTORY_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/draw-history/*/force-settle", "/api/admin/draw-history/force-settle-all").hasAuthority("PERM_DRAW_HISTORY_FORCE_SETTLE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/draw-history/supplement").hasAuthority("PERM_DRAW_HISTORY_SUPPLEMENT")
                        .requestMatchers(HttpMethod.GET, "/api/admin/unsettled-orders", "/api/admin/unsettled-orders/**").hasAuthority("PERM_UNSETTLED_ORDER_READ")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/unsettled-orders/**").hasAuthority("PERM_UNSETTLED_ORDER_DELETE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/order-corrections", "/api/admin/order-corrections/**").hasAuthority("PERM_ORDER_CORRECTION_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/order-corrections/**").hasAuthority("PERM_ORDER_CORRECTION_MANAGE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/online-players", "/api/admin/online-players/**").hasAuthority("PERM_ONLINE_PLAYER_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/online-players/*/disconnect").hasAuthority("PERM_ONLINE_PLAYER_DISCONNECT")
                        .requestMatchers(HttpMethod.POST, "/api/admin/online-players/*/messages").hasAuthority("PERM_ONLINE_PLAYER_MESSAGE")
                        .requestMatchers("/api/me/admin-notices", "/api/me/admin-notices/**").access(nonChatOnly())
                        .requestMatchers(HttpMethod.GET, "/api/admin/settings").hasAuthority("PERM_PLATFORM_SETTINGS_READ")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/settings").hasAuthority("PERM_PLATFORM_SETTINGS_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/settings/delete-all-accounts").hasAuthority("PERM_PLATFORM_SETTINGS_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/platform/public-settings").access(nonChatOnly())
                        .requestMatchers(HttpMethod.GET, "/api/admin/report-networks", "/api/admin/report-networks/**").hasAuthority("PERM_REPORT_NETWORK_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/report-networks").hasAuthority("PERM_REPORT_NETWORK_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/report-networks/**").hasAuthority("PERM_REPORT_NETWORK_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/game-settings", "/api/admin/game-settings/**").hasAuthority("PERM_GAME_SETTINGS_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/game-settings").hasAuthority("PERM_GAME_SETTINGS_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/game-settings/**").hasAuthority("PERM_GAME_SETTINGS_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/game-settings/**").hasAuthority("PERM_GAME_SETTINGS_WRITE")
                        .requestMatchers("/api/admin/password").hasAuthority("PERM_PLATFORM_PASSWORD_MANAGE")
                        .requestMatchers(HttpMethod.PATCH, "/api/admin/report-networks/**").hasAuthority("PERM_REPORT_NETWORK_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/report-networks/**").hasAuthority("PERM_REPORT_NETWORK_WRITE")
                        .requestMatchers("/api/agent", "/api/agent/**").hasAuthority("PERM_AGENT_CONSOLE_READ")
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
                        .requestMatchers("/api/demo/game/current", "/api/demo/game/catalog", "/api/demo/game/bets/summary").hasAuthority("PERM_GAME_CURRENT_READ")
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
