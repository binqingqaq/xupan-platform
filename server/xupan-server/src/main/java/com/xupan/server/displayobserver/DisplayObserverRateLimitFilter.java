package com.xupan.server.displayobserver;

import com.xupan.server.auth.domain.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class DisplayObserverRateLimitFilter extends OncePerRequestFilter {

    private final DisplayObserverRateLimiter rateLimiter;

    public DisplayObserverRateLimitFilter(DisplayObserverRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                && user.isDisplayObserver()) {
            long retryAfter = rateLimiter.checkRead(request.getRemoteAddr(),
                    user.scope().substring(DisplayObserverAccessService.SCOPE_PREFIX.length()));
            if (retryAfter > 0) {
                response.setStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", Long.toString(retryAfter));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"code\":\"DISPLAY_OBSERVER_RATE_LIMITED\","
                        + "\"message\":\"只读观察请求过于频繁，请稍后重试\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
