package io.acme.gateway.control;

import io.acme.gateway.policy.RouteSpec;
import io.acme.gateway.policy.RouteValidator;
import io.acme.gateway.policy.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

@Service
public class Store {
    final JdbcTemplate db;
    final ObjectMapper json;
    final RouteValidator validator;
    private final KeyRegistry keys;

    Store(
            JdbcTemplate d,
            ObjectMapper j,
            @Value("${upstream.allowed}") Set<String> a,
            KeyRegistry keys) {
        this.keys = keys;
        db = d;
        json = j;
        validator = new RouteValidator(a);
    }

    String encode(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid document");
        }
    }

    <T> T decode(String s, Class<T> c) {
        try {
            return json.readValue(s, c);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid document", e);
        }
    }

    List<Map<String, Object>> list(String kind) {
        return db.query(
                "select id,revision,body::text from registry where kind=? order by id limit 10000",
                (rs, n) ->
                        Map.of(
                                "id",
                                rs.getString(1),
                                "revision",
                                rs.getLong(2),
                                "body",
                                decode(rs.getString(3), Map.class)),
                kind);
    }

    @Transactional
    public Map<String, Object> save(
            String kind, String id, Map<String, Object> body, Long revision, String actor) {
        if (!Set.of("apis", "routes", "consumers", "rate-limits", "quotas", "plans").contains(kind)
                || !id.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IllegalArgumentException("invalid resource");
        if (kind.equals("routes")) {
            var route = decode(encode(body), RouteSpec.class);
            validator.validate(List.of(route));
            if (!id.equals(route.id())) throw new IllegalArgumentException("route id mismatch");
            if (!Boolean.FALSE.equals(body.get("enabled")))
                db.queryForObject(
                        "select id from registry where kind='apis' and id=?",
                        String.class,
                        route.apiId());
        }
        if (kind.equals("consumers")
                && (!(body.get("tenant") instanceof String t) || !t.matches("[a-zA-Z0-9_-]{1,64}")))
            throw new IllegalArgumentException("tenant required");
        if (kind.equals("consumers") && revision != null) {
            String current =
                    db.queryForObject(
                            "select body->>'tenant' from registry where kind='consumers' and id=?",
                            String.class,
                            id);
            if (!Objects.equals(current, body.get("tenant")))
                throw new IllegalArgumentException("consumer tenant is immutable");
        }
        if (body.containsKey("enabled") && !(body.get("enabled") instanceof Boolean))
            throw new IllegalArgumentException("enabled must be boolean");
        if (kind.equals("rate-limits") || kind.equals("quotas") || kind.equals("plans")) {
            if (!(body.get("tenant") instanceof String t) || !t.matches("[a-zA-Z0-9_-]{1,64}"))
                throw new IllegalArgumentException("policy tenant required");
            for (String field :
                    kind.equals("rate-limits")
                            ? List.of("rate", "burst")
                            : kind.equals("plans")
                            ? List.of("rate", "burst", "dailyQuota", "monthlyQuota")
                            : List.of("dailyQuota", "monthlyQuota")) {
                if (!(body.get(field) instanceof Number number)
                        || number.longValue() < 1
                        || number.doubleValue() != number.longValue()
                        || number.longValue() > 9007199254740991L)
                    throw new IllegalArgumentException("invalid policy value");
            }
        }
        if (revision == null)
            db.update(
                    "insert into registry(kind,id,revision,body) values (?,?,1,?::jsonb)",
                    kind,
                    id,
                    encode(body));
        else if (db.update(
                "update registry set body=?::jsonb,revision=revision+1 where kind=? and"
                        + " id=? and revision=?",
                encode(body),
                kind,
                id,
                revision)
                != 1) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
        audit(actor, "save", kind + ":" + id);
        return Map.of("id", id, "revision", revision == null ? 1 : revision + 1);
    }

    @Transactional
    public void delete(String kind, String id, long revision, String actor) {
        if (kind.equals("consumers")) {
            if (db.update(
                    "update registry set"
                            + " body=jsonb_set(body,'{enabled}','false'),revision=revision+1"
                            + " where kind=? and id=? and revision=?",
                    kind,
                    id,
                    revision)
                    != 1) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
            keys.revokeConsumer(id);
        } else {
            if (db.update(
                    "delete from registry where kind=? and id=? and revision=?",
                    kind,
                    id,
                    revision)
                    != 1) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
            if (kind.equals("apis"))
                db.update(
                        "update registry set"
                                + " body=jsonb_set(body,'{enabled}','false'),revision=revision+1 where"
                                + " kind='routes' and body->>'apiId'=?",
                        id);
        }
        audit(actor, "delete", kind + ":" + id);
    }

    @Transactional
    public Snapshot publish(Long rollback, String actor) {
        db.queryForObject("select pg_advisory_xact_lock(44221)", Object.class);
        List<RouteSpec> routes =
                rollback == null
                        ? list("routes").stream()
                        .map(m -> resolve((Map<String, Object>) m.get("body")))
                        .toList()
                        : decode(
                        db.queryForObject(
                                "select body::text from configuration_versions"
                                        + " where version=?",
                                String.class,
                                rollback),
                        Snapshot.class)
                        .routes();
        validator.validate(routes);
        long v = db.queryForObject("select nextval('configuration_version_seq')", Long.class);
        var s = new Snapshot(v, Instant.now(), routes);
        db.update(
                "insert into configuration_versions(version,body) values (?,?::jsonb)",
                v,
                encode(s));
        audit(actor, "publish", Long.toString(v));
        return s;
    }

    RouteSpec resolve(Map<String, Object> draft) {
        var body = new LinkedHashMap<>(draft);
        String tenant = Objects.toString(body.get("tenant"), "");
        if (!Boolean.FALSE.equals(body.get("enabled"))) {
            var api =
                    decode(
                            db.queryForObject(
                                    "select body::text from registry where kind='apis' and id=?",
                                    String.class,
                                    body.get("apiId")),
                            Map.class);
            if (api.get("planId") != null) body.putIfAbsent("planId", api.get("planId"));
            if (Boolean.FALSE.equals(api.get("enabled"))) body.put("enabled", false);
            if (api.get("tenant") != null && !tenant.equals(api.get("tenant")))
                throw new IllegalArgumentException("API tenant mismatch");
        }

        for (var link :
                Map.of("rateLimitPolicyId", "rate-limits", "quotaPolicyId", "quotas").entrySet())
            if (body.get(link.getKey()) != null) {
                String policyId = Objects.toString(body.get(link.getKey()));
                var policy =
                        decode(
                                db.queryForObject(
                                        "select body::text from registry where kind=? and id=?",
                                        String.class,
                                        link.getValue(),
                                        policyId),
                                Map.class);
                if (!tenant.equals(policy.get("tenant")))
                    throw new IllegalArgumentException("policy tenant mismatch");
                for (String field :
                        link.getValue().equals("rate-limits")
                                ? List.of("rate", "burst")
                                : List.of("dailyQuota", "monthlyQuota")) {
                    if (!(policy.get(field) instanceof Number))
                        throw new IllegalArgumentException("invalid named policy");
                    body.put(field, policy.get(field));
                }
            }
        if (body.get("planId") != null) {
            var plan =
                    decode(
                            db.queryForObject(
                                    "select body::text from registry where kind='plans' and id=?",
                                    String.class,
                                    body.get("planId")),
                            Map.class);
            if (!tenant.equals(plan.get("tenant")))
                throw new IllegalArgumentException("plan tenant mismatch");
            for (String field : List.of("rate", "burst", "dailyQuota", "monthlyQuota")) {
                if (!(plan.get(field) instanceof Number limit)
                        || !(body.get(field) instanceof Number route))
                    throw new IllegalArgumentException("invalid plan");
                body.put(field, Math.min(limit.longValue(), route.longValue()));
            }
        }
        return decode(encode(body), RouteSpec.class);
    }

    Snapshot latest() {
        var values =
                db.query(
                        "select body::text from configuration_versions order by version desc limit"
                                + " 1",
                        (rs, n) -> decode(rs.getString(1), Snapshot.class));
        return values.isEmpty() ? new Snapshot(0, Instant.now(), List.of()) : values.getFirst();
    }

    void audit(String actor, String action, String target) {
        db.update(
                "insert into audit_events(actor,action,target) values (?,?,?)",
                actor,
                action,
                target);
    }
}
