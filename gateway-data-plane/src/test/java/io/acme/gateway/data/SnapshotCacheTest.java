package io.acme.gateway.data;

import io.acme.gateway.policy.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotCacheTest {
    @TempDir
    Path directory;

    @Test
    void legacyJsonDefaultsOnlyOptionalFields() {
        var mapper = new tools.jackson.databind.json.JsonMapper();
        var route = mapper.readValue("""
                {"id":"orders","apiId":"orders","tenant":"demo","path":"/api/v1/orders/**",
                 "host":null,"methods":["GET"],"upstreams":["http://orders:8091"],
                 "scopes":["orders:read"],"roles":[],"priority":0,"rate":100,"burst":100,
                 "dailyQuota":10000,"monthlyQuota":100000,"timeoutMs":3000,"concurrency":32,"enabled":true}
                """, io.acme.gateway.policy.RouteSpec.class);
        assertEquals(0, route.retries());
        assertEquals(Map.of(), route.headers());
        var legacy = mapper.readValue("{\"version\":1,\"publishedAt\":\"2026-11-15T00:00:00Z\",\"routes\":[]}", Snapshot.class);
        assertEquals(1, legacy.schemaVersion());
        assertThrows(Exception.class, () -> mapper.readValue("{\"id\":\"missing-required-policy\"}", io.acme.gateway.policy.RouteSpec.class));
    }

    @Test
    void persistedVerificationTimeIsNotExtendedOnRestart() throws Exception {
        var cache =
                new SnapshotCache(
                        directory.resolve("snapshot.json").toString(),
                        new tools.jackson.databind.json.JsonMapper());
        Instant verified = Instant.now().minusSeconds(90);
        var snapshot = new Snapshot(42, Instant.now(), List.of());
        cache.write(new SnapshotCache.Entry(snapshot, verified));
        assertEquals(snapshot, cache.read().snapshot());
        assertEquals(verified, cache.read().verifiedAt());
        assertEquals(1, Files.list(directory).count());
    }

    @Test
    void rejectsIncompatibleSchema() {
        assertThrows(
                IllegalArgumentException.class, () -> new Snapshot(1, Instant.now(), List.of(), 2));
    }

    @Test
    void corruptedCacheDoesNotCreateConfiguration() throws Exception {
        Path path = directory.resolve("snapshot.json");
        Files.writeString(path, "broken");
        assertThrows(
                Exception.class,
                () -> new SnapshotCache(path.toString(), new ObjectMapper()).read());
    }
}
