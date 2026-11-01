package com.xupan.server.config;

import com.xupan.server.displayobserver.DisplayObserverRateLimitFilter;
import com.xupan.server.displayobserver.DisplayObserverRateLimiter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DisplayObserverRateLimitConfiguration {

    @Bean
    DisplayObserverRateLimitFilter displayObserverRateLimitFilter(DisplayObserverRateLimiter rateLimiter) {
        return new DisplayObserverRateLimitFilter(rateLimiter);
    }

    @Bean
    static FilterRegistrationBean<DisplayObserverRateLimitFilter> disableObserverRateLimitServletRegistration(
            DisplayObserverRateLimitFilter filter) {
        FilterRegistrationBean<DisplayObserverRateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.setEnabled(false);
        return registration;
    }
}
