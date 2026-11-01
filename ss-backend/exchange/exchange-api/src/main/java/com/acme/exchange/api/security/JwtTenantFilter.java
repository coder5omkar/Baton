package com.acme.exchange.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs after the Spring Security filter chain and binds the tenant claim to the
 * request thread.
 */
@Component
@Profile("!local")
@Order(-90)
public class JwtTenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String tenantId = jwtAuthentication.getToken().getClaimAsString("tenant_id");
            if (tenantId == null || tenantId.isBlank()) {
                tenantId = jwtAuthentication.getToken().getClaimAsString("tid");
            }
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.set(tenantId);
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
