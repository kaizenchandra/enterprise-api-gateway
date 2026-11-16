package io.acme.gateway.control;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class KeyRegistry {
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;

    KeyRegistry(JdbcTemplate db, StringRedisTemplate redis) {
        this.db = db;
        this.redis = redis;
    }

    @Transactional
    public void create(String id, String consumer, String verifier, String value, long expiry) {
        db.queryForObject(
                "select id from registry where kind='consumers' and id=? and"
                        + " coalesce(body->>'enabled','true')='true' for update",
                String.class,
                consumer);
        db.update(
                "insert into api_keys(id,consumer_id,verifier,registry_value,expires_at) values"
                        + " (?,?,?,?,to_timestamp(?))",
                id,
                consumer,
                verifier,
                value,
                expiry);
        db.update("insert into api_key_events(key_id) values (?)", id);
    }

    @Transactional(timeout = 15)
    public void revoke(String id) {
        db.queryForObject("select id from api_keys where id=? for update", String.class, id);
        db.update("update api_keys set revoked=true where id=?", id);
        db.update("insert into api_key_events(key_id) values (?)", id);
        redis.delete("key:" + id);
    }

    @Transactional(timeout = 15)
    public void reconcile(String id) {
        var record =
                db.queryForMap(
                        "select registry_value,revoked,extract(epoch from expires_at)::bigint as"
                                + " expiry from api_keys where id=? for update",
                        id);
        long remaining =
                ((Number) record.get("expiry")).longValue() - Instant.now().getEpochSecond();
        if (Boolean.TRUE.equals(record.get("revoked"))
                || remaining <= 0
                || record.get("registry_value") == null) redis.delete("key:" + id);
        else
            redis.opsForValue()
                    .set(
                            "key:" + id,
                            (String) record.get("registry_value"),
                            Duration.ofSeconds(remaining));
        db.update("delete from api_key_events where key_id=?", id);
    }

    @Transactional
    public void revokeConsumer(String consumer) {
        var keys =
                db.queryForList(
                        "select id from api_keys where consumer_id=? and not revoked order by id"
                                + " for update",
                        String.class,
                        consumer);
        for (String id : keys) {
            db.update("update api_keys set revoked=true where id=?", id);
            db.update("insert into api_key_events(key_id) values (?)", id);
            redis.delete("key:" + id);
        }
    }

    public List<Map<String, Object>> inspect(String consumer) {
        return db.queryForList(
                "select id,consumer_id,expires_at,revoked from api_keys where consumer_id=? order"
                        + " by id",
                consumer);
    }
}
