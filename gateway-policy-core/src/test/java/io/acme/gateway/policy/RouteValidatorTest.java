package io.acme.gateway.policy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RouteValidatorTest {
    RouteValidator v = new RouteValidator(Set.of("http://orders:8091"));

    RouteSpec route(String id, String path, String upstream, int priority) {
        return new RouteSpec(
                id,
                "orders",
                "tenant",
                path,
                null,
                Set.of("GET"),
                List.of(upstream),
                Set.of("read"),
                Set.of(),
                priority,
                10,
                20,
                100,
                1000,
                1000,
                10,
                true);
    }

    @Test
    void valid() {
        assertDoesNotThrow(
                () ->
                        v.validate(
                                List.of(route("a", "/api/v1/orders/**", "http://orders:8091", 0))));
    }

    @Test
    void ssrf() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        v.validate(
                                List.of(
                                        route(
                                                "a",
                                                "/api/v1/orders/**",
                                                "http://169.254.169.254",
                                                0))));
    }

    @Test
    void conflict() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        v.validate(
                                List.of(
                                        route("a", "/api/v1/orders/**", "http://orders:8091", 0),
                                        route(
                                                "b",
                                                "/api/v1/orders/admin/**",
                                                "http://orders:8091",
                                                0))));
    }

    @Test
    void precedence() {
        assertDoesNotThrow(
                () ->
                        v.validate(
                                List.of(
                                        route("a", "/api/v1/orders/**", "http://orders:8091", 0),
                                        route(
                                                "b",
                                                "/api/v1/orders/admin/**",
                                                "http://orders:8091",
                                                -1))));
    }

    @Test
    void pathTraversal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> v.validate(List.of(route("a", "/api/v1/../**", "http://orders:8091", 0))));
    }

    @Test
    void duplicateId() {
        var a = route("a", "/api/v1/orders/**", "http://orders:8091", 0);
        assertThrows(IllegalArgumentException.class, () -> v.validate(List.of(a, a)));
    }

    @Test
    void immutableSnapshot() {
        var routes = new ArrayList<RouteSpec>();
        var s = new Snapshot(1, java.time.Instant.now(), routes);
        routes.add(route("a", "/api/v1/orders/**", "http://orders:8091", 0));
        assertEquals(0, s.routes().size());
    }

    @Test
    void refusesUnsafeRetry() {
        var r = route("a", "/api/v1/orders/**", "http://orders:8091", 0);
        var unsafe =
                new RouteSpec(
                        r.id(),
                        r.apiId(),
                        r.tenant(),
                        r.path(),
                        null,
                        Set.of("POST"),
                        r.upstreams(),
                        r.scopes(),
                        r.roles(),
                        0,
                        10,
                        10,
                        100,
                        1000,
                        1000,
                        10,
                        true,
                        1);
        assertThrows(IllegalArgumentException.class, () -> v.validate(List.of(unsafe)));
    }

    @Test
    void permitsOneReadOnlyRetry() {
        var r = route("a", "/api/v1/orders/**", "http://orders:8091", 0);
        var safe =
                new RouteSpec(
                        r.id(),
                        r.apiId(),
                        r.tenant(),
                        r.path(),
                        null,
                        Set.of("GET"),
                        r.upstreams(),
                        r.scopes(),
                        r.roles(),
                        0,
                        10,
                        10,
                        100,
                        1000,
                        1000,
                        10,
                        true,
                        1);
        assertDoesNotThrow(() -> v.validate(List.of(safe)));
    }
}
