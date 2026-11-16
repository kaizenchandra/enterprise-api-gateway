package io.acme.gateway.data;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("configurationHealth")
public class ConfigurationHealth implements ReactiveHealthIndicator {
    private final Configurations configs;

    ConfigurationHealth(Configurations c) {
        configs = c;
    }

    public Mono<Health> health() {
        return Mono.just(
                (configs.fresh() ? Health.up() : Health.down())
                        .withDetail("version", configs.version())
                        .build());
    }
}
