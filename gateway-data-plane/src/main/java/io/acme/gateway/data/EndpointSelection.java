package io.acme.gateway.data;

import io.acme.gateway.policy.RouteSpec;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
public class EndpointSelection implements GlobalFilter, Ordered {
    public int getOrder() {
        return 10001;
    }

    public Mono<Void> filter(ServerWebExchange e, GatewayFilterChain c) {
        Route r = e.getAttribute(GATEWAY_ROUTE_ATTR);
        RouteSpec p = (RouteSpec) r.getMetadata().get("policy");
        URI old = e.getAttribute(GATEWAY_REQUEST_URL_ATTR);
        String endpoint =
                p.upstreams()
                        .get(
                                java.util.concurrent.ThreadLocalRandom.current()
                                        .nextInt(p.upstreams().size()));
        e.getAttributes()
                .put(
                        GATEWAY_REQUEST_URL_ATTR,
                        URI.create(
                                endpoint
                                        + old.getRawPath()
                                        + (old.getRawQuery() == null
                                        ? ""
                                        : "?" + old.getRawQuery())));
        return c.filter(e);
    }
}
