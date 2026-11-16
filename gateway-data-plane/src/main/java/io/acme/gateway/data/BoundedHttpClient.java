package io.acme.gateway.data;

import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.config.HttpClientFactory;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.config.HttpClientSslConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.List;

@Configuration
public class BoundedHttpClient {
    @Bean(destroyMethod = "dispose")
    ConnectionProvider upstreamPool() {
        return ConnectionProvider.builder("enterprise-upstream")
                .maxConnections(512)
                .pendingAcquireMaxCount(256)
                .pendingAcquireTimeout(Duration.ofMillis(500))
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(5))
                .evictInBackground(Duration.ofSeconds(30))
                .metrics(true)
                .build();
    }

    @Bean
    HttpClientFactory boundedHttpClientFactory(
            HttpClientProperties properties,
            ServerProperties server,
            HttpClientSslConfigurer ssl,
            List<HttpClientCustomizer> customizers,
            ConnectionProvider upstreamPool) {
        return new HttpClientFactory(properties, server, ssl, customizers) {
            @Override
            protected ConnectionProvider buildConnectionProvider(HttpClientProperties ignored) {
                return upstreamPool;
            }
        };
    }
}
