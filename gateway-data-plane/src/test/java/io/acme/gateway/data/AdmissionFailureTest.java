package io.acme.gateway.data;

import io.acme.gateway.policy.RouteSpec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class AdmissionFailureTest {
    @Test
    void jwksOrDecoderStallHasBoundedDeadline() {
        assertEquals(503, execute(Mono.never(), true));
    }

    @Test
    void missingCredentialsCannotDispatch() {
        assertEquals(401, execute(Mono.never(), false));
    }

    @Test
    void verifiedWrongTenantCannotDispatch() {
        var jwt =
                Jwt.withTokenValue("verified")
                        .header("alg", "RS256")
                        .subject("consumer")
                        .claim("tenant", "other")
                        .claim("scope", "orders:read")
                        .build();
        assertEquals(403, execute(Mono.just(jwt), true));
    }

    int execute(Mono<Jwt> decoded, boolean bearer) {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        when(decoder.decode("mock")).thenReturn(decoded);
        Configurations configs = mock(Configurations.class);
        when(configs.fresh()).thenReturn(true);
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        var spec =
                new RouteSpec(
                        "orders",
                        "orders",
                        "demo",
                        "/api/v1/orders/**",
                        null,
                        Set.of("GET"),
                        List.of("http://orders:8091"),
                        Set.of("orders:read"),
                        Set.of(),
                        0,
                        10,
                        10,
                        100,
                        1000,
                        1000,
                        10,
                        true);
        Route route = mock(Route.class);
        when(route.getMetadata()).thenReturn(Map.of("policy", spec));
        var request = MockServerHttpRequest.get("/api/v1/orders");
        if (bearer) request.header("Authorization", "Bearer mock");
        var exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        exchange.getResponse().getHeaders().setContentLength(999);
        new Admission(decoder, redis, configs, new SimpleMeterRegistry())
                .filter(
                        exchange,
                        e -> {
                            fail("upstream must not be dispatched");
                            return Mono.empty();
                        })
                .block(Duration.ofSeconds(4));
        assertEquals(
                exchange.getResponse()
                        .getBodyAsString()
                        .block()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        .length,
                exchange.getResponse().getHeaders().getContentLength());
        verifyNoInteractions(redis);
        return exchange.getResponse().getStatusCode().value();
    }
}
