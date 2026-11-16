package io.acme.gateway.example;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendWebSocketTest {
    static final KeyPair PAIR = keys();
    @LocalServerPort
    int port;

    static KeyPair keys() {
        try {
            var g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add(
                "IDENTITY_PUBLIC_KEY",
                () -> Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded()));
        p.add("management.tracing.enabled", () -> false);
    }

    String assertion() throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer("enterprise-gateway")
                        .audience("example-services")
                        .subject("consumer")
                        .claim("tenant", "demo")
                        .claim("scopes", List.of("orders:read"))
                        .expirationTime(Date.from(Instant.now().plusSeconds(30)))
                        .build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        token.sign(new RSASSASigner((RSAPrivateKey) PAIR.getPrivate()));
        return token.serialize();
    }

    @Test
    void signedHandshakeAndEchoThroughRealServer() throws Exception {
        var message = new CompletableFuture<String>();
        var listener =
                new WebSocket.Listener() {
                    public void onOpen(WebSocket s) {
                        s.request(1);
                    }

                    public CompletionStage<?> onText(
                            WebSocket s, CharSequence value, boolean last) {
                        message.complete(value.toString());
                        s.request(1);
                        return null;
                    }

                    public void onError(WebSocket s, Throwable e) {
                        message.completeExceptionally(e);
                    }
                };
        var ws =
                HttpClient.newHttpClient()
                        .newWebSocketBuilder()
                        .header("X-Gateway-Identity", assertion())
                        .buildAsync(
                                URI.create("ws://localhost:" + port + "/api/v1/events/echo"),
                                listener)
                        .get(5, TimeUnit.SECONDS);
        try {
            ws.sendText("hello", true).join();
            assertEquals("echo:hello", message.get(5, TimeUnit.SECONDS));
        } finally {
            ws.sendClose(1000, "done").join();
        }
    }
}
