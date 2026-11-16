package io.acme.gateway.control;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class KeyRegistryIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    JdbcTemplate db;
    StringRedisTemplate redis;
    LettuceConnectionFactory connection;
    KeyRegistry keys;

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        db = new JdbcTemplate(source);
        db.update("delete from api_key_events");
        db.update("delete from api_keys");
        db.update("delete from registry");
        db.update(
                "insert into registry(kind,id,revision,body) values"
                        + " ('consumers','consumer',1,'{\"tenant\":\"tenant\"}'::jsonb) on conflict do"
                        + " nothing");
        connection =
                new LettuceConnectionFactory(
                        redisContainer.getHost(), redisContainer.getMappedPort(6379));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        var proxy = new ProxyFactory(new KeyRegistry(db, redis));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(source),
                        new AnnotationTransactionAttributeSource()));
        keys = (KeyRegistry) proxy.getProxy();
    }

    @AfterEach
    void close() {
        connection.destroy();
    }

    void issue() {
        keys.create(
                "key1",
                "consumer",
                "a".repeat(64),
                "verifier|consumer|tenant|read||" + Instant.now().plusSeconds(90).getEpochSecond(),
                Instant.now().plusSeconds(90).getEpochSecond());
        keys.reconcile("key1");
    }

    @Test
    void lostRedisRegistryRestoresOnlyActiveKeys() {
        issue();
        assertNotNull(redis.opsForValue().get("key:key1"));
        redis.delete("key:key1");
        keys.reconcile("key1");
        assertNotNull(redis.opsForValue().get("key:key1"));
        keys.revoke("key1");
        keys.reconcile("key1");
        assertNull(redis.opsForValue().get("key:key1"));
        assertEquals(0, db.queryForObject("select count(*) from api_key_events", Integer.class));
    }

    @Test
    void concurrentReconciliationCannotResurrectRevocation() throws Exception {
        issue();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                for (int i = 0; i < 20; i++) keys.reconcile("key1");
                            });
            var b = executor.submit(() -> keys.revoke("key1"));
            a.get();
            b.get();
        }
        keys.reconcile("key1");
        assertNull(redis.opsForValue().get("key:key1"));
        assertTrue(
                db.queryForObject("select revoked from api_keys where id='key1'", Boolean.class));
    }

    Store store() {
        var json =
                new tools.jackson.databind.json.JsonMapper();
        var source = (javax.sql.DataSource) db.getDataSource();
        var proxy = new ProxyFactory(new Store(db, json, Set.of("http://orders:8091"), keys));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(source),
                        new AnnotationTransactionAttributeSource()));
        return (Store) proxy.getProxy();
    }

    @Test
    void staleConsumerDeletionDoesNotRevokeAndValidDeletionPreventsIssuance() {
        issue();
        Store store = store();
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> store.delete("consumers", "consumer", 99, "operator"));
        assertNotNull(redis.opsForValue().get("key:key1"));
        store.delete("consumers", "consumer", 1, "operator");
        assertNull(redis.opsForValue().get("key:key1"));
        assertThrows(
                org.springframework.dao.EmptyResultDataAccessException.class,
                () ->
                        keys.create(
                                "key2",
                                "consumer",
                                "b".repeat(64),
                                "value",
                                java.time.Instant.now().plusSeconds(90).getEpochSecond()));
    }

    @Test
    void deletingApiDisablesItsRoutesBeforePublication() {
        Store store = store();
        store.save("apis", "orders", Map.of("id", "orders", "tenant", "tenant"), null, "operator");
        var route =
                new io.acme.gateway.policy.RouteSpec(
                        "orders",
                        "orders",
                        "tenant",
                        "/api/v1/orders/**",
                        null,
                        Set.of("GET"),
                        List.of("http://orders:8091"),
                        Set.of(),
                        Set.of(),
                        0,
                        10,
                        10,
                        100,
                        1000,
                        1000,
                        10,
                        true);
        var body = new tools.jackson.databind.json.JsonMapper().convertValue(route, Map.class);
        store.save("routes", "orders", body, null, "operator");
        assertTrue(store.publish(null, "operator").routes().getFirst().enabled());
        store.delete("apis", "orders", 1, "operator");
        assertFalse(store.publish(null, "operator").routes().getFirst().enabled());
    }
}
