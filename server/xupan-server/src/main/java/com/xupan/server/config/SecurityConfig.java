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
                        // 中心上游接单面（/api/center/**）用**代理 Token** 认证，不是会话认证，
                        // 因此在这里放行，由 CenterOrderController 内部校验 Token 并自行返回 401。
                        // 注意：这条放行只覆盖 /api/center/**，不影响 /api/admin/**（超管面仍需权限）。
                        .requestMatchers("/api/center", "/api/center/**").permitAll()
                        // 顺序要紧：更具体的 orders/** 必须排在 agents/** 之前，否则会被上面那条吃掉，
                        // 变成"看订单需要账户管理权限"，与"只看订单不看账户"的设计不符。
                        .requestMatchers(HttpMethod.GET, "/api/admin/center/agents/orders",
                                        "/api/admin/center/agents/orders/**")
                        .hasAuthority("PERM_CENTER_ORDER_READ")
                        .requestMatchers("/api/admin/center/agents", "/api/admin/center/agents/**")
                        .hasAuthority("PERM_CENTER_AGENT_MANAGE")
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
                        // 代理后台接口（/api/agent/**，BY220 robot.php）：只认 AGENT_CONSOLE_READ。
                        // V76 已从 ADMIN / SUB_ACCOUNT 撤销该权限，因此收敛后只有机器账号能调（符合预期）。
                        .requestMatchers("/api/agent", "/api/agent/**").hasAuthority("PERM_AGENT_CONSOLE_READ")
                        .requestMatchers("/api/admin/users", "/api/admin/users/**").hasAuthority("PERM_USER_MANAGE")
                        // 全局赔率/限额配置（`/api/admin/player-desk/betting-config`）的现状事实：
                        //   * 它**原本就挂在 player-desk 路径下**，但当前**没有任何前端调用方**
                        //     （`api.getBettingConfig()` 的唯一调用点 AdminWelcomeControlPanel.vue 的 GLOBAL 分支，
                        //     随平台侧玩家工作台一起删除；该组件现只挂 /console，机器账号恒为 MACHINE 级别）；
                        //   * 因此 GET 不再给平台账号任何豁免：整段 `/api/admin/player-desk/**` 只有
                        //     AGENT_CONSOLE_READ（机器账号）能过，"超管不碰玩家"可用一条断言锁死。
                        //   * 唯一保留的 PUT 规则是**既有配置**（不是本次新增），且**不能删**：
                        //     GameBettingConfigService 没有任何权限校验，删掉这条会让机器账号能改**全局**
                        //     赔率/限额（PlayerDeskScopeTest.machineAccountCannotWriteGlobalBettingConfig 锁的就是
                        //     "机器只能读全局配置、不能写"）。若要连它一起收敛，必须先给该服务加 USER_MANAGE 校验，
                        //     属另议。
                        // 若平台侧将来确实需要读写全局赔率/限额，应**另开归属正确的端点**
                        // （对齐 BY220 admin.php 的「设置」），不要回到 player-desk 路径下开后门。
                        .requestMatchers(HttpMethod.PUT, "/api/admin/player-desk/betting-config",
                                "/api/admin/player-desk/betting-config/**")
                        .hasAuthority("PERM_USER_MANAGE")
                        // 玩家/托运营接口（BY220 robot.php 的代理后台，机器账号）：
                        // 严格对齐 BY220 —— application/admin/controller/* 从不查 db('user')，超管对玩家没有任何
                        // 操作面，因此这里只认 AGENT_CONSOLE_READ（只有机器账号可调）；
                        // 超管（USER_MANAGE）与子账号（AGENT_PLAYER_MANAGE）一律 403，数据范围由服务层收敛为 MACHINE。
                        .requestMatchers("/api/admin/player-desk", "/api/admin/player-desk/**")
                        .hasAuthority("PERM_AGENT_CONSOLE_READ")
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
