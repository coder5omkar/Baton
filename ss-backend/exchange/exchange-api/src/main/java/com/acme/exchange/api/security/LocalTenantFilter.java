package com.acme.exchange.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Local-only tenant selection. Production derives the tenant from the JWT.
 */
@Component
@Profile("local")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LocalTenantFilter extends OncePerRequestFilter {

    public static final String DEFAULT_TENANT = "tenant-dev";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String tenantId = request.getHeader("X-Tenant-Id");
        TenantContext.set(tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
