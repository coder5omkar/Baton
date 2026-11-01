package com.acme.exchange.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Local-only subscriber selection for polling APIs. Production derives the
 * subscriber from the authenticated principal.
 */
@Component
@Profile("local")
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class LocalSubscriberFilter extends OncePerRequestFilter {

    public static final String DEFAULT_SUBSCRIBER = "analytics";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String subscriberId = request.getHeader("X-Subscriber-Id");
        SubscriberContext.set(
                subscriberId == null || subscriberId.isBlank() ? DEFAULT_SUBSCRIBER : subscriberId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SubscriberContext.clear();
        }
    }
}
