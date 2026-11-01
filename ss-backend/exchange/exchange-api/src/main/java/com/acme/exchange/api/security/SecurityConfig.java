package com.acme.exchange.api.security;

import com.acme.exchange.api.config.ExchangeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Profile("local")
    SecurityFilterChain localFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Profile("!local")
    SecurityFilterChain securedFilterChain(HttpSecurity http, NimbusJwtDecoder jwtDecoder) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/internal/**").hasAuthority("SCOPE_internal.activate")
                        .requestMatchers(HttpMethod.POST, "/v1/flows/*/messages")
                        .hasAuthority("SCOPE_exchange.publish")
                        .requestMatchers(HttpMethod.GET, "/v1/flows/*/publications/*")
                        .hasAuthority("SCOPE_exchange.publish")
                        .requestMatchers("/v1/deliveries/**")
                        .hasAuthority("SCOPE_exchange.consume")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder)))
                .build();
    }

    @Bean
    @Profile("!local")
    NimbusJwtDecoder jwtDecoder(ExchangeProperties properties) {
        String issuer = properties.getSecurity().getIssuer();
        String audience = properties.getSecurity().getAudience();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<Object>("aud", aud -> audienceMatches(aud, audience)),
                SecurityConfig::requireTenantClaim
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private static boolean audienceMatches(Object aud, String audience) {
        if (aud instanceof String value) {
            return value.equals(audience);
        }
        if (aud instanceof Collection<?> values) {
            return values.contains(audience);
        }
        return false;
    }

    private static OAuth2TokenValidatorResult requireTenantClaim(Jwt jwt) {
        String tenantId = jwt.getClaimAsString("tenant_id");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = jwt.getClaimAsString("tid");
        }
        if (tenantId == null || tenantId.isBlank()) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "JWT must contain a tenant_id or tid claim", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
