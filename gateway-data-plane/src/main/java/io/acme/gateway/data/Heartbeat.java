package io.acme.gateway.data;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class Heartbeat {
    private final String id =
            System.getenv().getOrDefault("HOSTNAME", java.util.UUID.randomUUID().toString());
    private final Configurations configs;
    private final ReactiveStringRedisTemplate redis;

    Heartbeat(Configurations c, ReactiveStringRedisTemplate r) {
        configs = c;
        redis = r;
    }

    @Scheduled(fixedDelay = 5000)
    void heartbeat() {
        redis.opsForHash()
                .put(
                        "gateways",
                        id,
                        "{\"version\":"
                                + configs.version()
                                + ",\"fresh\":"
                                + configs.fresh()
                                + ",\"lastSeen\":"
                                + Instant.now().getEpochSecond()
                                + "}")
                .timeout(Duration.ofSeconds(1))
                .subscribe(v -> {
                }, e -> {
                });
    }
}
