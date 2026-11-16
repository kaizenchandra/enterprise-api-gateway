package io.acme.gateway.data;

import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;

@Component
public class ProtocolGuard implements WebFilter, Ordered {
    public int getOrder() {
        return -200;
    }

    public Mono<Void> filter(ServerWebExchange e, WebFilterChain c) {
        var h = e.getRequest().getHeaders();
        String p = e.getRequest().getURI().getRawPath().toLowerCase(Locale.ROOT);
        if (p.contains("%")
                || p.contains("..")
                || p.contains("//")
                || p.contains(";")
                || p.contains("\\")
                || (h.containsHeader("Transfer-Encoding") && h.containsHeader("Content-Length"))
                || h.getOrEmpty("Content-Length").size() > 1) {
            e.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return e.getResponse().setComplete();
        }
        if (h.getContentLength() > 1048576) {
            e.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            return e.getResponse().setComplete();
        }
        if (h.getContentLength() < 0
                && Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
                .contains(e.getRequest().getMethod())) {
            e.getResponse().setStatusCode(HttpStatus.LENGTH_REQUIRED);
            return e.getResponse().setComplete();
        }
        e.getResponse().getHeaders().set("X-Content-Type-Options", "nosniff");
        return c.filter(e);
    }
}
