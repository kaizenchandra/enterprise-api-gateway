package io.acme.gateway.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.WebFilter;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
public class BackendIdentity {
    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity h) {
        return h.csrf(c -> c.disable()).authorizeExchange(a -> a.anyExchange().permitAll()).build();
    }

    @Bean
    ReactiveJwtDecoder internalDecoder(@Value("${IDENTITY_PUBLIC_KEY}") String encoded)
            throws Exception {
        var key =
                (RSAPublicKey)
                        KeyFactory.getInstance("RSA")
                                .generatePublic(
                                        new X509EncodedKeySpec(
                                                Base64.getDecoder().decode(encoded)));
        var decoder = NimbusReactiveJwtDecoder.withPublicKey(key).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(java.time.Duration.ofSeconds(5)),
                        new JwtIssuerValidator("enterprise-gateway"),
                        jwt ->
                                jwt.getAudience().contains("example-services")
                                        && jwt.getExpiresAt() != null
                                        && jwt.getSubject() != null
                                        && jwt.getClaimAsString("tenant") != null
                                        ? OAuth2TokenValidatorResult.success()
                                        : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token"))));
        return decoder;
    }

    @Bean
    WebFilter verifyInternalIdentity(ReactiveJwtDecoder internalDecoder) {
        return (exchange, chain) -> {
            if (!exchange.getRequest().getPath().value().startsWith("/api/"))
                return chain.filter(exchange);
            String assertion = exchange.getRequest().getHeaders().getFirst("X-Gateway-Identity");
            if (assertion == null) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
            return internalDecoder
                    .decode(assertion)
                    .timeout(java.time.Duration.ofSeconds(1))
                    .flatMap(
                            jwt -> {
                                String path = exchange.getRequest().getPath().value();
                                String[] segments = path.split("/");
                                String api = segments.length > 3 ? segments[3] : "";
                                boolean read =
                                        exchange.getRequest().getMethod()
                                                == org.springframework.http.HttpMethod.GET
                                                || exchange.getRequest().getMethod()
                                                == org.springframework.http.HttpMethod.HEAD;
                                String required =
                                        switch (api) {
                                            case "payments" -> "payments:write";
                                            case "inventory" -> read ? "inventory:read" : "inventory:write";
                                            default -> read ? "orders:read" : "orders:write";
                                        };
                                if (!java.util.Optional.ofNullable(
                                                jwt.getClaimAsStringList("scopes"))
                                        .orElse(java.util.List.of())
                                        .contains(required)) {
                                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                                    return exchange.getResponse().setComplete();
                                }
                                var request =
                                        exchange.getRequest()
                                                .mutate()
                                                .headers(
                                                        h -> {
                                                            h.set(
                                                                    "X-Tenant-ID",
                                                                    jwt.getClaimAsString("tenant"));
                                                            h.set("X-User-ID", jwt.getSubject());
                                                            h.remove("X-Gateway-Identity");
                                                        })
                                                .build();
                                return chain.filter(exchange.mutate().request(request).build());
                            })
                    .onErrorResume(
                            JwtException.class,
                            e -> {
                                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                                return exchange.getResponse().setComplete();
                            });
        };
    }
}
