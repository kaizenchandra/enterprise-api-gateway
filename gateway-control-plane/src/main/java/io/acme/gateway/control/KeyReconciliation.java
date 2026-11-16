package io.acme.gateway.control;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KeyReconciliation {
    private final JdbcTemplate db;
    private final KeyRegistry keys;
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private String runId = "";

    KeyReconciliation(
            JdbcTemplate db, KeyRegistry keys, StringRedisTemplate redis, MeterRegistry meters) {
        this.db = db;
        this.keys = keys;
        this.redis = redis;
        this.meters = meters;
        meters.gauge(
                "gateway.keys.pending",
                this,
                x -> db.queryForObject("select count(*) from api_key_events", Long.class));
    }

    @Scheduled(fixedDelay = 4000)
    public void reconcile() {
        try {
            String current =
                    redis.execute(
                            (org.springframework.data.redis.core.RedisCallback<String>)
                                    c -> c.serverCommands().info("server").getProperty("run_id"));
            if (current != null && !current.equals(runId)) {
                db.update(
                        "insert into api_key_events(key_id) select id from api_keys where"
                                + " expires_at>now()");
                runId = current;
            }
            for (String id :
                    db.queryForList(
                            "select distinct key_id from api_key_events order by key_id limit 200",
                            String.class))
                try {
                    keys.reconcile(id);
                } catch (Exception e) {
                    meters.counter("gateway.keys.reconciliation.failures").increment();
                    break;
                }
        } catch (Exception e) {
            meters.counter("gateway.keys.reconciliation.failures").increment();
        }
    }
}
