package io.acme.gateway.example;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;

import java.util.Map;

@Configuration
public class Streaming {
    @Bean
    SimpleUrlHandlerMapping sockets() {
        WebSocketHandler echo =
                session ->
                        session.send(
                                session.receive()
                                        .map(
                                                message ->
                                                        session.textMessage(
                                                                "echo:"
                                                                        + message
                                                                        .getPayloadAsText())));
        return new SimpleUrlHandlerMapping(Map.of("/api/v1/events/echo", echo), -1);
    }

    @Bean
    WebSocketHandlerAdapter websocketAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @RestController
    static class Events {
        @GetMapping(value = "/api/v1/events/stream", produces = "text/event-stream")
        Flux<String> events() {
            return Flux.interval(java.time.Duration.ofMillis(100)).take(10).map(n -> "event-" + n);
        }
    }
}
