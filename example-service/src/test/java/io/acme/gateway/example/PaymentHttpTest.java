package io.acme.gateway.example;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentHttpTest {
    WebTestClient http = WebTestClient.bindToController(new Backend()).build();

    WebTestClient.ResponseSpec pay(String key, int amount, String query) {
        return http.post()
                .uri("/api/v1/payments" + query)
                .header("X-Tenant-ID", "tenant")
                .header("Idempotency-Key", key)
                .bodyValue(Map.of("amount", amount))
                .exchange();
    }

    @Test
    void idempotentReplay() {
        var first =
                pay("key", 100, "")
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        var second =
                pay("key", 100, "")
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertEquals(first.get("id"), second.get("id"));
    }

    @Test
    void changedPayloadIsHttpConflict() {
        pay("key", 100, "").expectStatus().isOk();
        pay("key", 101, "").expectStatus().isEqualTo(409);
    }

    @Test
    void simulatedFailureIsHttp503() {
        pay("key", 100, "?fail=true").expectStatus().isEqualTo(503);
    }
}
