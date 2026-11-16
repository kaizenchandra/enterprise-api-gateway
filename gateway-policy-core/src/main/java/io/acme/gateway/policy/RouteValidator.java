package io.acme.gateway.policy;

import java.net.URI;
import java.util.*;

public final class RouteValidator {
    private final Set<String> allowed;

    public RouteValidator(Set<String> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    private static void bad(String s) {
        throw new IllegalArgumentException(s);
    }

    public void validate(List<RouteSpec> routes) {
        if (routes.size() > 10000) throw new IllegalArgumentException("too many routes");
        Map<String, List<Long>> quotas = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (var r : routes) {
            if (r.id() == null || !r.id().matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(r.id()))
                bad("invalid/duplicate id");
            if (r.tenant() == null || !r.tenant().matches("[a-zA-Z0-9_-]{1,64}"))
                bad("tenant required");
            if (r.apiId() == null || !r.apiId().matches("[a-zA-Z0-9_-]{1,64}")) bad("api required");
            if (r.path() == null || !r.path().matches("/api/v[0-9]+/[a-zA-Z0-9/_-]+/\\*\\*"))
                bad("restricted versioned path required");
            if (r.headers().size() > 4) bad("too many header predicates");
            r.headers()
                    .forEach(
                            (name, value) -> {
                                if (!Set.of("X-API-Version", "X-Release").contains(name)
                                        || !value.matches("[a-zA-Z0-9._-]{1,64}"))
                                    bad("unsupported header predicate");
                            });
            if (r.host() != null && !r.host().matches("[a-zA-Z0-9.-]{1,253}")) bad("invalid host");
            if (r.retries() < 0
                    || r.retries() > 1
                    || (r.retries() > 0 && !Set.of("GET", "HEAD").containsAll(r.methods())))
                bad("retry budget requires GET/HEAD only and at most one extra attempt");
            if (r.methods().isEmpty()
                    || !Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
                    .containsAll(r.methods())) bad("invalid methods");
            if (r.upstreams().isEmpty() || r.upstreams().size() > 16) bad("upstream count");
            for (String s : r.upstreams()) {
                URI u = URI.create(s);
                if (!Set.of("http", "https", "ws", "wss").contains(u.getScheme())
                        || !allowed.contains(s)
                        || u.getUserInfo() != null
                        || u.getQuery() != null
                        || u.getFragment() != null) bad("upstream not explicitly allowed");
            }
            var previous =
                    quotas.putIfAbsent(
                            r.tenant() + ":" + r.apiId(),
                            List.of(r.dailyQuota(), r.monthlyQuota()));
            if (previous != null && !previous.equals(List.of(r.dailyQuota(), r.monthlyQuota())))
                bad("inconsistent tenant/API quotas");
            if (r.rate() < 1
                    || r.burst() < 1
                    || r.burst() > 1000000
                    || r.rate() > 1000000
                    || r.dailyQuota() < 1
                    || r.monthlyQuota() < 1
                    || r.dailyQuota() > 9007199254740991L
                    || r.monthlyQuota() > 9007199254740991L
                    || r.timeoutMs() < 50
                    || r.timeoutMs() > 30000
                    || r.concurrency() < 1
                    || r.concurrency() > 10000) bad("invalid policy bounds");
        }
        for (int i = 0; i < routes.size(); i++)
            for (int j = i + 1; j < routes.size(); j++) {
                var a = routes.get(i);
                var b = routes.get(j);
                boolean hosts =
                        a.host() == null || b.host() == null || a.host().equalsIgnoreCase(b.host());
                String ap = a.path().substring(0, a.path().length() - 2),
                        bp = b.path().substring(0, b.path().length() - 2);
                boolean disjointHeaders =
                        a.headers().entrySet().stream()
                                .anyMatch(
                                        e ->
                                                b.headers().containsKey(e.getKey())
                                                        && !e.getValue()
                                                        .equals(
                                                                b.headers()
                                                                        .get(e.getKey())));
                if (!disjointHeaders
                        && a.enabled()
                        && b.enabled()
                        && hosts
                        && (ap.startsWith(bp) || bp.startsWith(ap))
                        && !Collections.disjoint(a.methods(), b.methods())
                        && a.priority() == b.priority())
                    bad("ambiguous routes require distinct priorities");
            }
    }
}
