package io.acme.gateway.data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class Security {
    @Bean
    SecurityWebFilterChain chain(ServerHttpSecurity h) {
        return h.csrf(c -> c.disable()).authorizeExchange(a -> a.anyExchange().permitAll()).build();
    }

    @Bean
    ReactiveJwtDecoder decoder(
            @Value("${security.jwks}") String jwks, @Value("${security.issuer}") String issuer) {
        var d =
                NimbusReactiveJwtDecoder.withJwkSetUri(jwks)
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        d.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        j ->
                                j.getAudience().contains("gateway")
                                        && j.getExpiresAt() != null
                                        && j.getSubject() != null
                                        && j.getClaimAsString("tenant") != null
                                        && "Bearer".equals(j.getClaimAsString("typ"))
                                        ? OAuth2TokenValidatorResult.success()
                                        : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token"))));
        return d;
    }
}
