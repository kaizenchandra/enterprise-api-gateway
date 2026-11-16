package io.acme.gateway.data;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.net.InetSocketAddress;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtSecurityTest {
    static RSAKey key;
    static HttpServer server;
    static ReactiveJwtDecoder decoder;

    @BeforeAll
    static void setup() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("trusted").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/jwks",
                e -> {
                    byte[] b =
                            new JWKSet(key.toPublicJWK())
                                    .toString()
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(200, b.length);
                    e.getResponseBody().write(b);
                    e.close();
                });
        server.start();
        decoder =
                new Security()
                        .decoder(
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks",
                                "https://issuer.test");
    }

    @AfterAll
    static void close() {
        server.stop(0);
    }

    String token(
            String issuer, String audience, long expires, String kid, String tenant, String type)
            throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject("consumer")
                        .audience(audience)
                        .expirationTime(new Date(System.currentTimeMillis() + expires))
                        .claim("tenant", tenant)
                        .claim("typ", type)
                        .claim("scope", "orders:read")
                        .build();
        var jwt =
                new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void valid() throws Exception {
        assertEquals(
                "consumer",
                decoder.decode(
                                token(
                                        "https://issuer.test",
                                        "gateway",
                                        60000,
                                        "trusted",
                                        "demo",
                                        "Bearer"))
                        .block()
                        .getSubject());
    }

    @Test
    void wrongIssuer() throws Exception {
        var t = token("https://evil.test", "gateway", 60000, "trusted", "demo", "Bearer");
        assertThrows(Exception.class, () -> decoder.decode(t).block());
    }

    @Test
    void wrongAudience() throws Exception {
        var t = token("https://issuer.test", "other", 60000, "trusted", "demo", "Bearer");
        assertThrows(Exception.class, () -> decoder.decode(t).block());
    }

    @Test
    void expired() throws Exception {
        var t = token("https://issuer.test", "gateway", -120000, "trusted", "demo", "Bearer");
        assertThrows(Exception.class, () -> decoder.decode(t).block());
    }

    @Test
    void unknownKey() throws Exception {
        var t = token("https://issuer.test", "gateway", 60000, "unknown", "demo", "Bearer");
        assertThrows(Exception.class, () -> decoder.decode(t).block());
    }

    @Test
    void tenantRequired() throws Exception {
        var t = token("https://issuer.test", "gateway", 60000, "trusted", null, "Bearer");
        assertThrows(Exception.class, () -> decoder.decode(t).block());
    }

    @Test
    void tokenType() throws Exception {
        var t = token("https://issuer.test", "gateway", 60000, "trusted", "demo", "ID");
        assertThrows(Exception.class, () -> decoder.decode(t).block());
    }
}
