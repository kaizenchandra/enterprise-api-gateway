package io.acme.gateway.data;

import io.acme.gateway.policy.RouteSpec;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
public class Admission implements GlobalFilter, Ordered {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Admission.class);
    private final ReactiveJwtDecoder jwt;
    private final DefaultRedisScript<Long> invalidAttempts = new DefaultRedisScript<>();
    private final ReactiveStringRedisTemplate redis;
    private final Configurations configs;
    private final MeterRegistry meters;
    private final ConcurrentMap<String, AtomicInteger> active = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> sockets = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();
    private IdentityAssertion assertions;
    private io.micrometer.observation.ObservationRegistry observations =
            io.micrometer.observation.ObservationRegistry.NOOP;

    Admission(
            ReactiveJwtDecoder j,
            ReactiveStringRedisTemplate r,
            Configurations c,
            MeterRegistry m) {
        jwt = j;
        redis = r;
        configs = c;
        meters = m;
        m.gauge(
                "gateway.websocket.active",
                sockets,
                map -> map.values().stream().mapToInt(AtomicInteger::get).sum());
        script.setLocation(new ClassPathResource("admit.lua"));
        script.setResultType(List.class);
        invalidAttempts.setLocation(new ClassPathResource("invalid-attempt.lua"));
        invalidAttempts.setResultType(Long.class);
    }

    @org.springframework.beans.factory.annotation.Autowired
    Admission(
            ReactiveJwtDecoder j,
            ReactiveStringRedisTemplate r,
            Configurations c,
            MeterRegistry m,
            IdentityAssertion assertions,
            io.micrometer.observation.ObservationRegistry observations) {
        this(j, r, c, m);
        this.assertions = assertions;
        this.observations = observations;
    }

    static Set<String> words(String s) {
        return s == null || s.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(s.split(" +")));
    }

    public static String hash(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public int getOrder() {
        return -100;
    }

    public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
        long started = System.nanoTime();
        if (!configs.fresh()) return reject(ex, 503, "configuration unavailable or stale");
        Route route = ex.getAttribute(GATEWAY_ROUTE_ATTR);
        RouteSpec p = (RouteSpec) route.getMetadata().get("policy");
        if (p == null) return reject(ex, 403, "unmanaged route");
        ex.getResponse()
                .beforeCommit(
                        () -> {
                            ex.getResponse()
                                    .getHeaders()
                                    .set("X-API-Version", p.path().split("/")[2]);
                            return Mono.empty();
                        });
        ex.getAttributes().put("policy.phase", "authentication");
        return observed("gateway.authentication", identity(ex))
                .timeout(Duration.ofSeconds(2))
                .flatMap(
                        id -> {
                            if (!p.tenant().equals(id.tenant())
                                    || !id.scopes().containsAll(p.scopes())
                                    || !id.roles().containsAll(p.roles()))
                                return reject(ex, 403, "access denied");
                            ex.getAttributes().put("policy.phase", "coordination");
                            String bucket =
                                    "{t:"
                                            + p.tenant()
                                            + "}:bucket:"
                                            + p.id()
                                            + ":"
                                            + hash(id.subject());
                            String quota = "{t:" + p.tenant() + "}:quota:" + p.apiId();
                            return observed(
                                    "gateway.admission",
                                    redis.execute(
                                                    script,
                                                    List.of(bucket, quota),
                                                    List.of(
                                                            "" + p.rate(),
                                                            "" + p.burst(),
                                                            "" + p.dailyQuota(),
                                                            "" + p.monthlyQuota(),
                                                            ""
                                                                    + YearMonth.now(
                                                                    ZoneOffset
                                                                            .UTC)))
                                            .single()
                                            .timeout(Duration.ofMillis(500)))
                                    .flatMap(
                                            result -> {
                                                if (((Number) result.getFirst()).longValue() != 1) {
                                                    ex.getResponse()
                                                            .getHeaders()
                                                            .set(
                                                                    "Retry-After",
                                                                    ""
                                                                            + Math.max(
                                                                            1,
                                                                            ((Number)
                                                                                    result
                                                                                            .get(
                                                                                                    1))
                                                                                    .longValue()));
                                                    return reject(
                                                            ex, 429, "admission limit exceeded");
                                                }
                                                var count =
                                                        active.computeIfAbsent(
                                                                p.id(), k -> new AtomicInteger());
                                                if (count.incrementAndGet() > p.concurrency()) {
                                                    count.decrementAndGet();
                                                    return reject(
                                                            ex, 429, "route concurrency exceeded");
                                                }
                                                if (total.incrementAndGet() > 4096) {
                                                    total.decrementAndGet();
                                                    count.decrementAndGet();
                                                    return reject(ex, 503, "gateway saturated");
                                                }
                                                boolean upgraded =
                                                        "websocket"
                                                                .equalsIgnoreCase(
                                                                        ex.getRequest()
                                                                                .getHeaders()
                                                                                .getUpgrade());
                                                String socketKey =
                                                        p.tenant()
                                                                + ":"
                                                                + p.id()
                                                                + ":"
                                                                + hash(id.subject());
                                                if (upgraded) {
                                                    AtomicBoolean allowed = new AtomicBoolean();
                                                    sockets.compute(
                                                            socketKey,
                                                            (key, current) -> {
                                                                if (current == null)
                                                                    current = new AtomicInteger();
                                                                if (current.get() < 4) {
                                                                    current.incrementAndGet();
                                                                    allowed.set(true);
                                                                }
                                                                return current;
                                                            });
                                                    if (!allowed.get()) {
                                                        count.decrementAndGet();
                                                        total.decrementAndGet();
                                                        return reject(
                                                                ex,
                                                                429,
                                                                "consumer WebSocket connection"
                                                                        + " limit exceeded");
                                                    }
                                                }
                                                AtomicBoolean released = new AtomicBoolean();
                                                Runnable release =
                                                        () -> {
                                                            if (released.compareAndSet(
                                                                    false, true)) {
                                                                count.decrementAndGet();
                                                                total.decrementAndGet();
                                                                if (upgraded)
                                                                    sockets.computeIfPresent(
                                                                            socketKey,
                                                                            (key, current) ->
                                                                                    current
                                                                                            .decrementAndGet()
                                                                                            == 0
                                                                                            ? null
                                                                                            : current);
                                                            }
                                                        };
                                                reactor.netty.Connection connection;
                                                try {
                                                    connection =
                                                            upgraded
                                                                    ? holdSocket(ex, release)
                                                                    : null;
                                                } catch (Exception error) {
                                                    release.run();
                                                    return Mono.error(error);
                                                }
                                                var req =
                                                        ex.getRequest()
                                                                .mutate()
                                                                .headers(
                                                                        h -> {
                                                                            for (String name :
                                                                                    new ArrayList<>(
                                                                                            h
                                                                                                    .headerNames())) {
                                                                                String n =
                                                                                        name
                                                                                                .toLowerCase(
                                                                                                        Locale
                                                                                                                .ROOT);
                                                                                if (n.startsWith(
                                                                                        "x-user")
                                                                                        || n
                                                                                        .startsWith(
                                                                                                "x-tenant")
                                                                                        || n
                                                                                        .startsWith(
                                                                                                "x-role")
                                                                                        || n
                                                                                        .startsWith(
                                                                                                "x-authenticated")
                                                                                        || n
                                                                                        .startsWith(
                                                                                                "x-forwarded")
                                                                                        || n.equals(
                                                                                        "forwarded")
                                                                                        || n.equals(
                                                                                        "x-api-key")
                                                                                        || n.equals(
                                                                                        "authorization")
                                                                                        || n.equals(
                                                                                        "cookie")
                                                                                        || n.equals(
                                                                                        "x-gateway-identity"))
                                                                                    h.remove(name);
                                                                            }
                                                                            h.set(
                                                                                    "X-User-ID",
                                                                                    id.subject());
                                                                            h.set(
                                                                                    "X-Tenant-ID",
                                                                                    id.tenant());
                                                                            if (assertions != null)
                                                                                h.set(
                                                                                        "X-Gateway-Identity",
                                                                                        assertions
                                                                                                .sign(
                                                                                                        id));
                                                                        })
                                                                .build();
                                                long elapsed = System.nanoTime() - started;
                                                meters.timer("gateway.policy.duration")
                                                        .record(
                                                                elapsed,
                                                                java.util.concurrent.TimeUnit
                                                                        .NANOSECONDS);
                                                ex.getResponse()
                                                        .getHeaders()
                                                        .add(
                                                                "Server-Timing",
                                                                String.format(
                                                                        java.util.Locale.ROOT,
                                                                        "gateway-policy;dur=%.3f",
                                                                        elapsed / 1_000_000.0));
                                                ex.getAttributes().put("policy.phase", "upstream");
                                                return chain.filter(
                                                                ex.mutate().request(req).build())
                                                        .timeout(
                                                                "websocket"
                                                                        .equalsIgnoreCase(
                                                                                ex.getRequest()
                                                                                        .getHeaders()
                                                                                        .getUpgrade())
                                                                        ? Duration.ofSeconds(30)
                                                                        : Duration.ofMillis(
                                                                        p.timeoutMs()))
                                                        .doFinally(
                                                                signal -> {
                                                                    if (!upgraded) release.run();
                                                                    else if (signal
                                                                            == reactor.core
                                                                            .publisher
                                                                            .SignalType
                                                                            .ON_ERROR
                                                                            || signal
                                                                            == reactor.core
                                                                            .publisher
                                                                            .SignalType
                                                                            .CANCEL) {
                                                                        connection.dispose();
                                                                        release.run();
                                                                    }
                                                                });
                                            });
                        })
                .onErrorResume(
                        org.springframework.security.oauth2.core.OAuth2AuthenticationException
                                .class,
                        e -> invalid(ex))
                .onErrorResume(JwtException.class, e -> invalid(ex))
                .onErrorResume(
                        e -> {
                            String phase = ex.getAttributeOrDefault("policy.phase", "upstream");
                            meters.counter("gateway.dependency.failures", "phase", phase)
                                    .increment();
                            log.warn(
                                    "Gateway dependency failure phase={} route={} cause={}"
                                            + " frames={}",
                                    phase,
                                    p.id(),
                                    e.getClass().getSimpleName(),
                                    java.util.Arrays.stream(e.getStackTrace())
                                            .limit(8)
                                            .map(StackTraceElement::toString)
                                            .toList());
                            return reject(ex, 503, "dependency unavailable");
                        });
    }

    private reactor.netty.Connection holdSocket(ServerWebExchange exchange, Runnable release) {
        reactor.netty.http.server.HttpServerRequest nativeRequest =
                org.springframework.http.server.reactive.ServerHttpRequestDecorator
                        .getNativeRequest(exchange.getRequest());
        AtomicReference<reactor.netty.Connection> connection = new AtomicReference<>();
        nativeRequest.withConnection(
                channel -> {
                    connection.set(channel);
                    var lifetime =
                            reactor.core.scheduler.Schedulers.parallel()
                                    .schedule(
                                            channel::dispose,
                                            30,
                                            java.util.concurrent.TimeUnit.SECONDS);
                    reactor.netty.FutureMono.from(channel.channel().closeFuture())
                            .doFinally(
                                    signal -> {
                                        lifetime.dispose();
                                        release.run();
                                    })
                            .subscribe(v -> {
                            }, error -> release.run());
                });
        return java.util.Objects.requireNonNull(connection.get());
    }

    private <T> Mono<T> observed(String name, Mono<T> source) {
        return Mono.deferContextual(
                context -> {
                    var parent =
                            context.<io.micrometer.observation.Observation>getOrDefault(
                                    io.micrometer.observation.contextpropagation
                                            .ObservationThreadLocalAccessor.KEY,
                                    null);
                    var observation =
                            io.micrometer.observation.Observation.createNotStarted(
                                            name, observations)
                                    .parentObservation(parent)
                                    .start();
                    return source.doOnError(observation::error)
                            .doFinally(s -> observation.stop())
                            .contextWrite(
                                    c ->
                                            c.put(
                                                    io.micrometer.observation.contextpropagation
                                                            .ObservationThreadLocalAccessor.KEY,
                                                    observation));
                });
    }

    private Mono<Void> invalid(ServerWebExchange exchange) {
        // In focused filter tests there is no live Redis; production always uses the injected
        // signer.
        if (assertions == null) return reject(exchange, 401, "invalid credentials");
        String ip =
                exchange.getRequest().getRemoteAddress() == null
                        ? "unknown"
                        : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        return redis.execute(invalidAttempts, List.of("invalid:" + hash(ip)))
                .single()
                .timeout(Duration.ofMillis(500))
                .flatMap(
                        allowed -> {
                            if (allowed == 0) {
                                exchange.getResponse().getHeaders().set("Retry-After", "60");
                                return reject(
                                        exchange, 429, "invalid credential attempt limit exceeded");
                            }
                            return reject(exchange, 401, "invalid credentials");
                        });
    }

    private Mono<Identity> identity(ServerWebExchange e) {
        String key = e.getRequest().getHeaders().getFirst("X-API-Key"),
                auth = e.getRequest().getHeaders().getFirst("Authorization");
        if (key != null && auth != null)
            return Mono.error(new JwtException("ambiguous credentials"));
        if (key != null) {
            if (!key.matches("[a-f0-9-]{36}\\.[A-Za-z0-9_-]{43}"))
                return Mono.error(new JwtException("invalid key"));
            String[] parts = key.split("\\.");
            return redis.opsForValue()
                    .get("key:" + parts[0])
                    .timeout(Duration.ofMillis(500))
                    .switchIfEmpty(Mono.error(new JwtException("invalid key")))
                    .map(
                            v -> {
                                String[] fields = v.split("\\|", -1);
                                if (fields.length != 6
                                        || !MessageDigest.isEqual(
                                        fields[0].getBytes(StandardCharsets.UTF_8),
                                        hash(key).getBytes(StandardCharsets.UTF_8))
                                        || Long.parseLong(fields[5])
                                        < Instant.now().getEpochSecond())
                                    throw new JwtException("invalid key");
                                return new Identity(
                                        fields[1], fields[2], words(fields[3]), words(fields[4]));
                            });
        }
        if (auth == null || !auth.startsWith("Bearer "))
            return Mono.error(new JwtException("credentials required"));
        return jwt.decode(auth.substring(7))
                .map(
                        j ->
                                new Identity(
                                        j.getSubject(),
                                        j.getClaimAsString("tenant"),
                                        words(j.getClaimAsString("scope")),
                                        new HashSet<>(
                                                Optional.ofNullable(j.getClaimAsStringList("roles"))
                                                        .orElse(List.of()))));
    }

    private Mono<Void> reject(ServerWebExchange e, int status, String detail) {
        meters.counter("gateway.policy.rejections", "status", "" + status).increment();
        if (e.getResponse().isCommitted())
            return Mono.error(
                    new IllegalStateException("upstream response interrupted after commit"));
        e.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        e.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] payload =
                ("{\"type\":\"about:blank\",\"status\":"
                        + status
                        + ",\"detail\":\""
                        + detail
                        + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
        e.getResponse().getHeaders().remove("Transfer-Encoding");
        e.getResponse().getHeaders().remove("Content-Encoding");
        e.getResponse().getHeaders().setContentLength(payload.length);
        return e.getResponse().writeWith(Mono.just(e.getResponse().bufferFactory().wrap(payload)));
    }

    record Identity(String subject, String tenant, Set<String> scopes, Set<String> roles) {
    }
}
