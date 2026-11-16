package io.acme.gateway.data;

import io.acme.gateway.policy.RouteValidator;
import io.acme.gateway.policy.Snapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
public class Configurations {
    private final AtomicReference<State> state = new AtomicReference<>();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final Scheduler compiler = Schedulers.newBoundedElastic(1, 1, "configuration");
    private final org.springframework.context.ApplicationEventPublisher events;
    private final WebClient client;
    private final RouteValidator validator;
    private final RouteLocatorBuilder builder;
    private final SnapshotCache cache;
    private final MeterRegistry meters;
    private final long maxStale;

    Configurations(
            org.springframework.context.ApplicationEventPublisher events,
            WebClient.Builder web,
            RouteLocatorBuilder builder,
            @Value("${control.url}") String url,
            @Value("${control.token}") String token,
            @Value("${upstream.allowed}") Set<String> allowed,
            MeterRegistry meters,
            ObjectMapper json,
            @Value("${control.cache-file:}") String cacheFile,
            @Value("${control.max-stale-seconds:300}") long maxStale) {
        this.events = events;
        this.client =
                web.baseUrl(url)
                        .defaultHeader("X-Control-Token", token)
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                        .build();
        this.builder = builder;
        this.validator = new RouteValidator(allowed);
        this.cache = new SnapshotCache(cacheFile, json);
        this.meters = meters;
        this.maxStale = maxStale;
        meters.gauge("gateway.configuration.version", this, Configurations::version);
        meters.gauge("gateway.configuration.age", this, x -> x.age());
    }

    @Bean
    RouteLocator dynamicRoutes() {
        return () ->
                Flux.defer(
                        () -> {
                            State s = state.get();
                            return s == null ? Flux.empty() : Flux.fromIterable(s.routes());
                        });
    }

    public long version() {
        State s = state.get();
        return s == null ? 0 : s.snapshot().version();
    }

    long age() {
        State s = state.get();
        return s == null
                ? Long.MAX_VALUE
                : s.initialAge()
                + Math.max(0, (System.nanoTime() - s.adoptedAt()) / 1_000_000_000L);
    }

    public boolean fresh() {
        return state.get() != null && age() < maxStale;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        Mono.fromCallable(cache::read)
                .subscribeOn(compiler)
                .flatMap(
                        e -> {
                            if (Duration.between(e.verifiedAt(), Instant.now()).toSeconds()
                                    >= maxStale
                                    || e.verifiedAt().isAfter(Instant.now().plusSeconds(30)))
                                return Mono.empty();
                            return compile(e.snapshot())
                                    .doOnNext(
                                            routes ->
                                                    activate(
                                                            e.snapshot(),
                                                            routes,
                                                            e.verifiedAt(),
                                                            false));
                        })
                .subscribe(v -> {
                }, e -> failure("cache"));
    }

    @Scheduled(fixedDelay = 3000)
    public void poll() {
        if (!polling.compareAndSet(false, true)) return;
        client.get()
                .uri("/internal/snapshot")
                .retrieve()
                .bodyToMono(Snapshot.class)
                .timeout(Duration.ofSeconds(2))
                .publishOn(compiler)
                .flatMap(
                        s -> {
                            State old = state.get();
                            if (old != null && s.version() < old.snapshot().version())
                                return Mono.error(
                                        new IllegalArgumentException("version regression"));
                            if (old != null && s.version() == old.snapshot().version()) {
                                if (!s.routes().equals(old.snapshot().routes()))
                                    return Mono.error(
                                            new IllegalArgumentException(
                                                    "version content changed"));
                                activate(s, old.routes(), Instant.now(), true);
                                return Mono.just(old.routes());
                            }
                            return compile(s)
                                    .doOnNext(routes -> activate(s, routes, Instant.now(), true));
                        })
                .doFinally(signal -> polling.set(false))
                .subscribe(v -> {
                }, e -> failure("reconcile"));
    }

    private Mono<List<Route>> compile(Snapshot s) {
        validator.validate(s.routes());
        var b = builder.routes();
        for (var r : s.routes())
            if (r.enabled())
                b.route(
                        r.id(),
                        p -> {
                            var predicates =
                                    p.order(r.priority())
                                            .path(r.path())
                                            .and()
                                            .method(r.methods().toArray(String[]::new));
                            if (r.host() != null) predicates = predicates.and().host(r.host());
                            for (var header : r.headers().entrySet())
                                predicates =
                                        predicates
                                                .and()
                                                .header(
                                                        header.getKey(),
                                                        java.util.regex.Pattern.quote(
                                                                header.getValue()));
                            return predicates
                                    .filters(
                                            f -> {
                                                f.circuitBreaker(
                                                        c -> {
                                                            c.setName(r.id());
                                                            c.addStatusCode("500");
                                                            c.addStatusCode("502");
                                                            c.addStatusCode("503");
                                                            c.addStatusCode("504");
                                                        });
                                                if (r.retries() > 0)
                                                    f.retry(
                                                            retry ->
                                                                    retry.setRetries(r.retries())
                                                                            .setMethods(
                                                                                    org
                                                                                            .springframework
                                                                                            .http
                                                                                            .HttpMethod
                                                                                            .GET,
                                                                                    org
                                                                                            .springframework
                                                                                            .http
                                                                                            .HttpMethod
                                                                                            .HEAD)
                                                                            .setSeries()
                                                                            .setStatuses(
                                                                                    org
                                                                                            .springframework
                                                                                            .http
                                                                                            .HttpStatus
                                                                                            .BAD_GATEWAY,
                                                                                    org
                                                                                            .springframework
                                                                                            .http
                                                                                            .HttpStatus
                                                                                            .SERVICE_UNAVAILABLE,
                                                                                    org
                                                                                            .springframework
                                                                                            .http
                                                                                            .HttpStatus
                                                                                            .GATEWAY_TIMEOUT)
                                                                            .setBackoff(
                                                                                    Duration
                                                                                            .ofMillis(
                                                                                                    50),
                                                                                    Duration
                                                                                            .ofMillis(
                                                                                                    200),
                                                                                    2,
                                                                                    true)
                                                                            .setJitter(0.5)
                                                                            .setTimeout(
                                                                                    Duration
                                                                                            .ofMillis(
                                                                                                    r
                                                                                                            .timeoutMs())));
                                                return f;
                                            })
                                    .metadata("policy", r)
                                    .metadata("response-timeout", (long) r.timeoutMs())
                                    .uri(r.upstreams().getFirst());
                        });
        return b.build().getRoutes().collectList();
    }

    private void activate(
            Snapshot snapshot, List<Route> routes, Instant verified, boolean persist) {
        State old = state.get();
        if (old != null
                && (snapshot.version() < old.snapshot().version()
                || verified.isBefore(old.verifiedAt()))) return;
        boolean changed = old == null || snapshot.version() != old.snapshot().version();
        state.set(
                new State(
                        snapshot,
                        List.copyOf(routes),
                        verified,
                        System.nanoTime(),
                        Math.max(0, Duration.between(verified, Instant.now()).toSeconds())));
        if (changed)
            events.publishEvent(
                    new org.springframework.cloud.gateway.event.RefreshRoutesEvent(this));
        if (persist)
            try {
                cache.write(new SnapshotCache.Entry(snapshot, verified));
            } catch (Exception e) {
                failure("persist");
            }
    }

    private void failure(String phase) {
        meters.counter("gateway.configuration.failures", "phase", phase).increment();
    }

    @jakarta.annotation.PreDestroy
    void close() {
        compiler.dispose();
    }

    record State(
            Snapshot snapshot,
            List<Route> routes,
            Instant verifiedAt,
            long adoptedAt,
            long initialAge) {
    }
}
