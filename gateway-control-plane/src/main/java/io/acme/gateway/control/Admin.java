package io.acme.gateway.control;

import io.acme.gateway.policy.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
public class Admin {
    final org.springframework.jdbc.core.JdbcTemplate db;
    final Store store;
    final StringRedisTemplate redis;
    final String token;
    private KeyRegistry keys;

    Admin(
            Store s,
            StringRedisTemplate r,
            org.springframework.jdbc.core.JdbcTemplate db,
            @Value("${control.token}") String t) {
        if (t.length() < 32)
            throw new IllegalArgumentException("control token must be at least 32 characters");
        this.db = db;
        store = s;
        redis = r;
        token = t;
    }

    @org.springframework.beans.factory.annotation.Autowired
    Admin(
            Store s,
            StringRedisTemplate r,
            org.springframework.jdbc.core.JdbcTemplate db,
            @Value("${control.token}") String t,
            KeyRegistry keys) {
        this(s, r, db, t);
        this.keys = keys;
    }

    static String hash(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @GetMapping("/internal/snapshot")
    Snapshot snapshot(@RequestHeader("X-Control-Token") String t) {
        if (!MessageDigest.isEqual(
                t.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return store.latest();
    }

    @GetMapping("/api/v1/admin/{kind}")
    Object list(@PathVariable String kind) {
        if (kind.equals("audit-events"))
            return db.queryForList(
                    "select actor,action,target,created_at from audit_events order by id desc limit"
                            + " 100");
        if (kind.equals("gateways")) return redis.opsForHash().entries("gateways");
        return store.list(kind);
    }

    @GetMapping("/api/v1/admin/{kind}/{id}")
    Object get(@PathVariable String kind, @PathVariable String id) {
        return store.list(kind).stream()
                .filter(x -> x.get("id").equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/api/v1/admin/{kind}")
    ResponseEntity<?> create(
            @PathVariable String kind, @RequestBody Map<String, Object> body, Authentication a) {
        String id = Objects.toString(body.get("id"), UUID.randomUUID().toString());
        return ResponseEntity.status(201).body(store.save(kind, id, body, null, a.getName()));
    }

    @PatchMapping("/api/v1/admin/{kind}/{id}")
    Object update(
            @PathVariable String kind,
            @PathVariable String id,
            @RequestHeader("If-Match") long revision,
            @RequestBody Map<String, Object> body,
            Authentication a) {
        if (body.containsKey("id") && !id.equals(body.get("id")))
            throw new IllegalArgumentException("id cannot change");
        return store.save(kind, id, body, revision, a.getName());
    }

    @DeleteMapping("/api/v1/admin/{kind}/{id}")
    ResponseEntity<?> delete(
            @PathVariable String kind,
            @PathVariable String id,
            @RequestHeader("If-Match") long revision,
            Authentication a) {
        store.delete(kind, id, revision, a.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/configurations/publish")
    Snapshot publish(Authentication a) {
        return store.publish(null, a.getName());
    }

    @PostMapping("/api/v1/admin/configurations/{version}/rollback")
    Snapshot rollback(@PathVariable long version, Authentication a) {
        return store.publish(version, a.getName());
    }

    @GetMapping("/api/v1/admin/configurations/versions")
    Object versions() {
        return db.queryForList(
                "select version,created_at from configuration_versions order by version desc limit"
                        + " 100");
    }

    @GetMapping("/api/v1/admin/consumers/{consumer}/keys")
    Object inspectKeys(@PathVariable String consumer) {
        return keys.inspect(consumer);
    }

    @PostMapping("/api/v1/admin/consumers/{consumer}/keys")
    ResponseEntity<?> issue(
            @PathVariable String consumer, @RequestBody KeyRequest request, Authentication a) {
        return ResponseEntity.status(201).body(issueKey(consumer, request, a.getName()));
    }

    Map<String, Object> issueKey(String consumer, KeyRequest request, String actor) {
        if (request.days() < 1
                || request.days() > 365
                || request.scopes() == null
                || request.roles() == null)
            throw new IllegalArgumentException("invalid key policy");
        String tenant =
                db.queryForObject(
                        "select body->>'tenant' from registry where kind='consumers' and id=? and"
                                + " coalesce(body->>'enabled','true')='true'",
                        String.class,
                        consumer);
        for (String v :
                new HashSet<String>() {
                    {
                        addAll(request.scopes());
                        addAll(request.roles());
                    }
                })
            if (!v.matches("[a-zA-Z0-9:_-]{1,64}"))
                throw new IllegalArgumentException("invalid scope/role");
        String id = UUID.randomUUID().toString();
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String secret = id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        long expires = Instant.now().plus(Duration.ofDays(request.days())).getEpochSecond();
        String value =
                hash(secret)
                        + "|"
                        + consumer
                        + "|"
                        + tenant
                        + "|"
                        + String.join(" ", request.scopes())
                        + "|"
                        + String.join(" ", request.roles())
                        + "|"
                        + expires;
        if (keys != null) {
            keys.create(id, consumer, hash(secret), value, expires);
            keys.reconcile(id);
        } else {
            db.update(
                    "insert into api_keys(id,consumer_id,verifier,expires_at) values"
                            + " (?,?,?,to_timestamp(?))",
                    id,
                    consumer,
                    hash(secret),
                    expires);
            redis.opsForValue().set("key:" + id, value, Duration.ofDays(request.days()));
        }
        store.audit(actor, "issue-key", id);
        return Map.of("id", id, "secret", secret, "expiresAt", expires);
    }

    @DeleteMapping("/api/v1/admin/keys/{id}")
    ResponseEntity<?> revoke(@PathVariable String id, Authentication a) {
        if (keys != null) keys.revoke(id);
        else {
            redis.delete("key:" + id);
            db.update("update api_keys set revoked=true where id=?", id);
        }
        store.audit(a.getName(), "revoke-key", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/keys/{id}/rotate")
    Object rotate(@PathVariable String id, @RequestBody KeyRequest request, Authentication a) {
        String consumer =
                db.queryForObject(
                        "select consumer_id from api_keys where id=? and not revoked",
                        String.class,
                        id);
        var key = issueKey(consumer, request, a.getName());
        revoke(id, a);
        return key;
    }

    @GetMapping("/api/v1/admin/gateways/{id}/status")
    Object gateway(@PathVariable String id) {
        Object value = redis.opsForHash().get("gateways", id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return value;
    }

    @GetMapping("/api/v1/admin/consumers/{id}/usage")
    Object usage(@PathVariable String id) {
        String tenant =
                db.queryForObject(
                        "select body->>'tenant' from registry where kind='consumers' and id=?",
                        String.class,
                        id);
        var usage = new LinkedHashMap<String, Object>();
        for (var route : store.latest().routes())
            if (route.tenant().equals(tenant))
                usage.put(
                        route.apiId(),
                        redis.opsForHash().entries("{t:" + tenant + "}:quota:" + route.apiId()));
        return Map.of("consumer", id, "tenant", tenant, "tenantApiUsage", usage);
    }

    record KeyRequest(Set<String> scopes, Set<String> roles, int days) {
    }
}
