package io.acme.gateway.example;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

class BackendIdentityTest {
    KeyPair trusted;
    WebTestClient http;

    @BeforeEach
    void init() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trusted = generator.generateKeyPair();
        var configuration = new BackendIdentity();
        var decoder =
                configuration.internalDecoder(
                        Base64.getEncoder().encodeToString(trusted.getPublic().getEncoded()));
        http =
                WebTestClient.bindToController(new Backend())
                        .webFilter(configuration.verifyInternalIdentity(decoder))
                        .build();
    }

    String token(KeyPair pair, String audience, long expiry) throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer("enterprise-gateway")
                        .audience(audience)
                        .subject("consumer")
                        .claim("tenant", "trusted")
                        .claim("scopes", List.of("orders:read"))
                        .expirationTime(Date.from(Instant.now().plusSeconds(expiry)))
                        .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void untrustedHeadersCannotReachBackend() {
        http.get()
                .uri("/api/v1/orders")
                .header("X-Tenant-ID", "forged")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void signedContextOverwritesForgedTenant() throws Exception {
        http.get()
                .uri("/api/v1/orders")
                .header("X-Gateway-Identity", token(trusted, "example-services", 30))
                .header("X-Tenant-ID", "forged")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.tenant")
                .isEqualTo("trusted");
    }

    @Test
    void wrongAudienceRejected() throws Exception {
        http.get()
                .uri("/api/v1/orders")
                .header("X-Gateway-Identity", token(trusted, "other", 30))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void expiredContextRejected() throws Exception {
        http.get()
                .uri("/api/v1/orders")
                .header("X-Gateway-Identity", token(trusted, "example-services", -120))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void forgedSignatureRejected() throws Exception {
        var g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        http.get()
                .uri("/api/v1/orders")
                .header("X-Gateway-Identity", token(g.generateKeyPair(), "example-services", 30))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void readAssertionCannotInvokePaymentOperation() throws Exception {
        http.post()
                .uri("/api/v1/payments")
                .header("X-Gateway-Identity", token(trusted, "example-services", 30))
                .header("Idempotency-Key", "replay")
                .bodyValue(Map.of("amount", 1))
                .exchange()
                .expectStatus()
                .isForbidden();
    }
}
