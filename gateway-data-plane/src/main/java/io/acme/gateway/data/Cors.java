package io.acme.gateway.data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Arrays;
import java.util.List;

@Configuration
public class Cors {
    @Bean
    org.springframework.web.cors.reactive.CorsWebFilter corsWebFilter(
            @Value("${security.cors-origins:}") String configured) {
        var policy = new CorsConfiguration();
        var origins = Arrays.stream(configured.split(",")).filter(s -> !s.isBlank()).toList();
        for (String origin : origins)
            if (!origin.matches("https?://[a-zA-Z0-9.:-]+"))
                throw new IllegalArgumentException("explicit CORS origins required");
        policy.setAllowedOrigins(origins);
        policy.setAllowedMethods(
                List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        policy.setAllowedHeaders(
                List.of(
                        "Authorization",
                        "Content-Type",
                        "X-API-Key",
                        "Idempotency-Key",
                        "X-API-Version",
                        "X-Release"));
        policy.setExposedHeaders(List.of("Retry-After", "X-API-Version", "Server-Timing"));
        policy.setAllowCredentials(false);
        policy.setMaxAge(600L);
        var source = new org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", policy);
        return new org.springframework.web.cors.reactive.CorsWebFilter(source);
    }
}
