package io.acme.gateway.policy;

import java.time.Instant;
import java.util.List;

/**
 * Wire schema 1; zero denotes the legacy three-field envelope only.
 */
public record Snapshot(
        long version, Instant publishedAt, List<RouteSpec> routes, Integer schemaVersion) {
    public Snapshot(long version, Instant publishedAt, List<RouteSpec> routes) {
        this(version, publishedAt, routes, 1);
    }

    public Snapshot {
        if (schemaVersion == null || schemaVersion == 0) schemaVersion = 1;
        if (schemaVersion != 1)
            throw new IllegalArgumentException("unsupported configuration schema");
        if (version < 0 || publishedAt == null)
            throw new IllegalArgumentException("invalid configuration envelope");
        routes = List.copyOf(routes);
    }
}
