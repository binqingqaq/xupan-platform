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
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/actuator/health").permitAll()
                        .requestMatchers("/api/auth/logout", "/api/auth/me", "/api/auth/ws-ticket").authenticated()
                        .requestMatchers("/api/demo/game/current").hasAuthority("PERM_GAME_CURRENT_READ")
                        .requestMatchers("/api/demo/game/bets").hasAuthority("PERM_GAME_BET_PLACE")
                        .requestMatchers("/api/demo/account").hasAuthority("PERM_GAME_CURRENT_READ")
                        .requestMatchers("/api/demo/game/admin/**").hasAuthority("PERM_GAME_ODDS_WRITE")
                        .requestMatchers("/api/demo/admin/**").hasAuthority("PERM_USER_MANAGE")
                        .anyRequest().authenticated())
                .addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
