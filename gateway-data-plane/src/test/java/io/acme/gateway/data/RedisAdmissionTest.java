package io.acme.gateway.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class RedisAdmissionTest {
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    StringRedisTemplate a, b;
    LettuceConnectionFactory fa, fb;
    DefaultRedisScript<List> script;

    @BeforeEach
    void init() {
        fa = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        fa.afterPropertiesSet();
        fb = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        fb.afterPropertiesSet();
        a = new StringRedisTemplate(fa);
        b = new StringRedisTemplate(fb);
        a.getConnectionFactory().getConnection().serverCommands().flushDb();
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("admit.lua"));
        script.setResultType(List.class);
    }

    @AfterEach
    void close() {
        fa.destroy();
        fb.destroy();
    }

    long consume(StringRedisTemplate t, String consumer, long quota) {
        return ((Number)
                t.execute(
                                script,
                                List.of("{tenant}:b:" + consumer, "{tenant}:q"),
                                "1",
                                "1000",
                                "" + quota,
                                "" + quota,
                                "ignored")
                        .getFirst())
                .longValue();
    }

    @Test
    void twoClientsCannotOverspend() throws Exception {
        try (var pool = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<Future<Long>>();
            for (int i = 0; i < 200; i++) {
                final int n = i;
                tasks.add(pool.submit(() -> consume(n % 2 == 0 ? a : b, "c" + n, 50)));
            }
            long admitted = 0;
            for (var f : tasks) admitted += f.get();
            assertEquals(50, admitted);
        }
    }

    @Test
    void rolloverAndDeniedRequestsDoNotConsume() {
        assertEquals(1, consume(a, "a", 1));
        assertEquals(0, consume(b, "b", 1));
        a.opsForHash().putAll("{tenant}:q", Map.of("day", "-1", "month", "old"));
        assertEquals(1, consume(b, "c", 1));
        assertEquals("1", a.opsForHash().get("{tenant}:q", "d"));
    }

    @Test
    void burstIsBounded() {
        var keys = List.of("{tenant}:b", "{tenant}:q");
        for (int i = 0; i < 2; i++)
            assertEquals(
                    1L,
                    ((Number) a.execute(script, keys, "1", "2", "100", "100", "ignored").getFirst())
                            .longValue());
        assertEquals(
                0L,
                ((Number) b.execute(script, keys, "1", "2", "100", "100", "ignored").getFirst())
                        .longValue());
    }

    @Test
    void realCalendarBoundaryAndRollbackCannotResetSpentBudget() throws Exception {
        String source =
                new String(
                        new ClassPathResource("admit.lua").getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)
                        .replace("local now=redis.call('TIME')", "local now={tonumber(ARGV[6]),0}");
        var controlled = new DefaultRedisScript<List>(source, List.class);
        var keys = List.of("{calendar}:b", "{calendar}:q");
        long january = java.time.Instant.parse("2028-01-31T23:59:59Z").getEpochSecond();
        long february = january + 1;
        assertEquals(
                1L,
                ((Number)
                        a.execute(
                                        controlled,
                                        keys,
                                        "100",
                                        "100",
                                        "1",
                                        "2",
                                        "unused",
                                        "" + january)
                                .getFirst())
                        .longValue());
        assertEquals(
                0L,
                ((Number)
                        b.execute(
                                        controlled,
                                        keys,
                                        "100",
                                        "100",
                                        "1",
                                        "2",
                                        "unused",
                                        "" + january)
                                .getFirst())
                        .longValue());
        assertEquals(
                1L,
                ((Number)
                        b.execute(
                                        controlled,
                                        keys,
                                        "100",
                                        "100",
                                        "1",
                                        "2",
                                        "unused",
                                        "" + february)
                                .getFirst())
                        .longValue());
        assertEquals("2028-2", a.opsForHash().get("{calendar}:q", "month"));
        assertEquals(
                0L,
                ((Number)
                        a.execute(
                                        controlled,
                                        keys,
                                        "100",
                                        "100",
                                        "1",
                                        "2",
                                        "unused",
                                        "" + january)
                                .getFirst())
                        .longValue());
    }
}
