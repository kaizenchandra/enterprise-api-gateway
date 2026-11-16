package io.acme.gateway.policy;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record RouteSpec(
        String id,
        String apiId,
        String tenant,
        String path,
        String host,
        Set<String> methods,
        List<String> upstreams,
        Set<String> scopes,
        Set<String> roles,
        int priority,
        int rate,
        int burst,
        long dailyQuota,
        long monthlyQuota,
        int timeoutMs,
        int concurrency,
        boolean enabled,
        Integer retries,
        Map<String, String> headers) {
    public RouteSpec(
            String id,
            String apiId,
            String tenant,
            String path,
            String host,
            Set<String> methods,
            List<String> upstreams,
            Set<String> scopes,
            Set<String> roles,
            int priority,
            int rate,
            int burst,
            long dailyQuota,
            long monthlyQuota,
            int timeoutMs,
            int concurrency,
            boolean enabled) {
        this(
                id,
                apiId,
                tenant,
                path,
                host,
                methods,
                upstreams,
                scopes,
                roles,
                priority,
                rate,
                burst,
                dailyQuota,
                monthlyQuota,
                timeoutMs,
                concurrency,
                enabled,
                0,
                Map.of());
    }

    public RouteSpec(
            String id,
            String apiId,
            String tenant,
            String path,
            String host,
            Set<String> methods,
            List<String> upstreams,
            Set<String> scopes,
            Set<String> roles,
            int priority,
            int rate,
            int burst,
            long dailyQuota,
            long monthlyQuota,
            int timeoutMs,
            int concurrency,
            boolean enabled,
            int retries) {
        this(
                id,
                apiId,
                tenant,
                path,
                host,
                methods,
                upstreams,
                scopes,
                roles,
                priority,
                rate,
                burst,
                dailyQuota,
                monthlyQuota,
                timeoutMs,
                concurrency,
                enabled,
                retries,
                Map.of());
    }

    public RouteSpec {
        retries = retries == null ? 0 : retries;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        methods = Set.copyOf(methods);
        upstreams = List.copyOf(upstreams);
        scopes = Set.copyOf(scopes);
        roles = Set.copyOf(roles);
    }
}
