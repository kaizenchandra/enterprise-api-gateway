package io.acme.gateway.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController
public class Backend {
    private final Map<String, Map<String, Object>> orders = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> payments = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, java.util.concurrent.atomic.AtomicInteger> stocks =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, java.util.concurrent.atomic.AtomicInteger> attempts =
            new ConcurrentHashMap<>();

    @Value("${SERVICE_NAME:orders}")
    String service;

    private java.util.concurrent.atomic.AtomicInteger stock(String tenant) {
        return stocks.computeIfAbsent(
                tenant, k -> new java.util.concurrent.atomic.AtomicInteger(1000));
    }

    @GetMapping("/api/v{version}/{api}")
    Object list(@PathVariable String api, @RequestHeader("X-Tenant-ID") String tenant) {
        return Map.of(
                "service", service, "api", api, "tenant", tenant, "inventory", stock(tenant).get());
    }

    @PostMapping("/api/v{version}/orders")
    Mono<?> order(
            @RequestHeader("X-Tenant-ID") String tenant,
            @RequestBody Map<String, Object> body,
            @RequestParam(defaultValue = "0") int delay) {
        String id = UUID.randomUUID().toString();
        var order = Map.<String, Object>of("id", id, "tenant", tenant, "items", body);
        orders.put(tenant + ":" + id, order);
        return Mono.delay(Duration.ofMillis(Math.min(10000, Math.max(0, delay)))).map(x -> order);
    }

    @GetMapping("/api/v{version}/orders/{id}")
    ResponseEntity<?> order(@RequestHeader("X-Tenant-ID") String tenant, @PathVariable String id) {
        var o = orders.get(tenant + ":" + id);
        return o == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(o);
    }

    @PostMapping("/api/v{version}/payments")
    Mono<ResponseEntity<?>> payment(
            @RequestHeader("X-Tenant-ID") String tenant,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Map<String, Object> body,
            @RequestParam(defaultValue = "0") int delay,
            @RequestParam(defaultValue = "false") boolean fail) {
        attempts.computeIfAbsent(tenant, k -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
        if (key.length() > 128) return Mono.just(ResponseEntity.badRequest().build());
        if (fail) return Mono.just(ResponseEntity.status(503).build());
        var value =
                payments.computeIfAbsent(
                        tenant + ":" + key,
                        k ->
                                Map.of(
                                        "id",
                                        UUID.randomUUID().toString(),
                                        "status",
                                        "authorized",
                                        "request",
                                        Map.copyOf(body)));
        if (!value.get("request").equals(body))
            return Mono.just(ResponseEntity.status(409).build());
        return Mono.delay(Duration.ofMillis(Math.min(10000, Math.max(0, delay))))
                .map(x -> ResponseEntity.ok(value));
    }

    @GetMapping("/api/v{version}/resilience/{key}")
    ResponseEntity<?> flaky(@PathVariable String key, @RequestHeader("X-Tenant-ID") String tenant) {
        int attempt =
                attempts.computeIfAbsent(
                                "flaky:" + tenant + ":" + key,
                                k -> new java.util.concurrent.atomic.AtomicInteger())
                        .incrementAndGet();
        return attempt == 1
                ? ResponseEntity.status(503).build()
                : ResponseEntity.ok(Map.of("attempts", attempt));
    }

    @GetMapping("/api/v{version}/payments/diagnostics")
    Object diagnostics(@RequestHeader("X-Tenant-ID") String tenant) {
        return Map.of(
                "attempts",
                attempts.getOrDefault(tenant, new java.util.concurrent.atomic.AtomicInteger())
                        .get(),
                "effects",
                payments.keySet().stream().filter(k -> k.startsWith(tenant + ":")).count());
    }

    @PostMapping("/api/v{version}/inventory/reservations")
    ResponseEntity<?> reserve(@RequestHeader("X-Tenant-ID") String tenant) {
        for (; ; ) {
            int n = stock(tenant).get();
            if (n == 0) return ResponseEntity.status(409).build();
            if (stock(tenant).compareAndSet(n, n - 1))
                return ResponseEntity.ok(Map.of("remaining", n - 1));
        }
    }
}
