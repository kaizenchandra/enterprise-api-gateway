package io.acme.gateway.control;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class Security {
    @Bean
    SecurityFilterChain chain(HttpSecurity h) throws Exception {
        return h.headers(
                        headers ->
                                headers.contentSecurityPolicy(
                                        c ->
                                                c.policyDirectives(
                                                        "default-src 'self'; script-src 'self';"
                                                                + " style-src 'self'; base-uri 'none';"
                                                                + " frame-ancestors 'none'; object-src"
                                                                + " 'none'")))
                .csrf(c -> c.disable())
                .sessionManagement(
                        s ->
                                s.sessionCreationPolicy(
                                        org.springframework.security.config.http
                                                .SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/internal/**",
                                                "/",
                                                "/index.html",
                                                "/app.js",
                                                "/style.css",
                                                "/actuator/health/**",
                                                "/actuator/prometheus")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/v1/admin/**")
                                        .hasAuthority("SCOPE_gateway.read")
                                        .anyRequest()
                                        .hasAuthority("SCOPE_gateway.write"))
                .oauth2ResourceServer(o -> o.jwt(j -> {
                }))
                .build();
    }

    @Bean
    JwtDecoder decoder(
            @Value("${security.jwks}") String jwks, @Value("${security.issuer}") String issuer) {
        var d = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        d.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        j ->
                                j.getAudience().contains("gateway-admin")
                                        && j.getExpiresAt() != null
                                        && "Bearer".equals(j.getClaimAsString("typ"))
                                        ? OAuth2TokenValidatorResult.success()
                                        : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token"))));
        return d;
    }
}
