package io.acme.gateway.data;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolGuardTest {
    @Test
    void rejectsOversize() {
        var e =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/v1/orders")
                                .header("Content-Length", "1048577"));
        new ProtocolGuard()
                .filter(
                        e,
                        x -> {
                            fail("upstream invoked");
                            return Mono.empty();
                        })
                .block();
        assertEquals(413, e.getResponse().getStatusCode().value());
    }

    @Test
    void rejectsAmbiguousFraming() {
        var e =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/v1/orders")
                                .header("Content-Length", "1")
                                .header("Transfer-Encoding", "chunked"));
        new ProtocolGuard()
                .filter(
                        e,
                        x -> {
                            fail();
                            return Mono.empty();
                        })
                .block();
        assertEquals(400, e.getResponse().getStatusCode().value());
    }

    @Test
    void rejectsChunkedUpload() {
        var e = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/orders"));
        new ProtocolGuard()
                .filter(
                        e,
                        x -> {
                            fail();
                            return Mono.empty();
                        })
                .block();
        assertEquals(411, e.getResponse().getStatusCode().value());
    }

    @Test
    void hashIsDeterministic() {
        assertEquals(64, Admission.hash("secret").length());
        assertEquals(Admission.hash("secret"), Admission.hash("secret"));
        assertNotEquals(Admission.hash("secret"), Admission.hash("other"));
    }
}
