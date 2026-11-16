package io.acme.gateway.data;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "control.token=websocket-context-secret-at-least-thirty-two-characters",
                "security.issuer=https://issuer.test",
                "security.jwks=http://127.0.0.1:9/jwks",
                "management.server.port=0",
                "management.tracing.enabled=false"
        })
class GatewayWebSocketIntegrationTest {
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static DisposableServer backend;
    static com.sun.net.httpserver.HttpServer control;
    @LocalServerPort
    int port;
    @Autowired
    Configurations configurations;
    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;
    @MockitoBean
    ReactiveJwtDecoder decoder;
    @MockitoBean
    Heartbeat heartbeat;

    @DynamicPropertySource
    static void setup(DynamicPropertyRegistry properties) throws Exception {
        backend =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .route(
                                routes ->
                                        routes.ws(
                                                "/api/v1/events/echo",
                                                (in, out) ->
                                                        out.sendString(
                                                                in.receive()
                                                                        .asString()
                                                                        .map(
                                                                                value ->
                                                                                        "echo:"
                                                                                                + value))))
                        .bindNow();
        String upstream = "ws://127.0.0.1:" + backend.port();
        var route =
                new io.acme.gateway.policy.RouteSpec(
                        "events",
                        "events",
                        "demo",
                        "/api/v1/events/echo/**",
                        null,
                        Set.of("GET"),
                        List.of(upstream),
                        Set.of("read"),
                        Set.of(),
                        0,
                        100,
                        100,
                        1000,
                        1000,
                        3000,
                        10,
                        true);
        var snapshot = new io.acme.gateway.policy.Snapshot(1, Instant.now(), List.of(route));
        var mapper =
                new tools.jackson.databind.json.JsonMapper();
        byte[] json = mapper.writeValueAsBytes(snapshot);
        control =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        control.createContext(
                "/internal/snapshot",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, json.length);
                    exchange.getResponseBody().write(json);
                    exchange.close();
                });
        control.start();
        properties.add("control.url", () -> "http://127.0.0.1:" + control.getAddress().getPort());
        properties.add("upstream.allowed", () -> upstream);
        properties.add("spring.data.redis.host", redis::getHost);
        properties.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String privateKey =
                Base64.getEncoder()
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        properties.add("security.identity-private-key", () -> privateKey);
    }

    @AfterAll
    static void close() {
        if (control != null) control.stop(0);
        if (backend != null) backend.disposeNow();
    }

    @Test
    void echoWorksThroughActualGatewayAfterUpgrade() throws Exception {
        when(decoder.decode("test"))
                .thenReturn(
                        Mono.just(
                                Jwt.withTokenValue("test")
                                        .header("alg", "RS256")
                                        .subject("consumer")
                                        .claim("tenant", "demo")
                                        .claim("scope", "read")
                                        .expiresAt(Instant.now().plusSeconds(60))
                                        .build()));
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (configurations.version() != 1 && System.nanoTime() < until) Thread.sleep(50);
        assertEquals(1, configurations.version());
        var response = new CompletableFuture<String>();
        var listener =
                new WebSocket.Listener() {
                    public void onOpen(WebSocket socket) {
                        socket.request(1);
                    }

                    public CompletionStage<?> onText(
                            WebSocket socket, CharSequence value, boolean last) {
                        response.complete(value.toString());
                        socket.request(1);
                        return null;
                    }

                    public void onError(WebSocket socket, Throwable e) {
                        response.completeExceptionally(e);
                    }
                };
        var socket =
                HttpClient.newHttpClient()
                        .newWebSocketBuilder()
                        .header("Authorization", "Bearer test")
                        .buildAsync(
                                URI.create("ws://localhost:" + port + "/api/v1/events/echo"),
                                listener)
                        .get(5, TimeUnit.SECONDS);
        try {
            socket.sendText("hello", true).join();
            assertEquals("echo:hello", response.get(5, TimeUnit.SECONDS));
            assertEquals(1.0, meters.get("gateway.websocket.active").gauge().value());
            var connections = new ArrayList<WebSocket>();
            try {
                for (int i = 0; i < 3; i++)
                    connections.add(
                            HttpClient.newHttpClient()
                                    .newWebSocketBuilder()
                                    .header("Authorization", "Bearer test")
                                    .buildAsync(
                                            URI.create(
                                                    "ws://localhost:"
                                                            + port
                                                            + "/api/v1/events/echo"),
                                            new WebSocket.Listener() {
                                                public void onOpen(WebSocket socket) {
                                                    socket.request(1);
                                                }
                                            })
                                    .get(5, TimeUnit.SECONDS));
                assertEquals(4.0, meters.get("gateway.websocket.active").gauge().value());
                var failure =
                        assertThrows(
                                ExecutionException.class,
                                () ->
                                        HttpClient.newHttpClient()
                                                .newWebSocketBuilder()
                                                .header("Authorization", "Bearer test")
                                                .buildAsync(
                                                        URI.create(
                                                                "ws://localhost:"
                                                                        + port
                                                                        + "/api/v1/events/echo"),
                                                        new WebSocket.Listener() {
                                                        })
                                                .get(5, TimeUnit.SECONDS));
                assertEquals(
                        429,
                        ((WebSocketHandshakeException) failure.getCause())
                                .getResponse()
                                .statusCode());
            } finally {
                for (var extra : connections) extra.sendClose(1000, "done").join();
            }

        } finally {
            socket.sendClose(1000, "done").join();
        }
    }
}
