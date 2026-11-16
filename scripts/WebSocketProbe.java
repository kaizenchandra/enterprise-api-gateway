import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

class WebSocketProbe {
    static WebSocket open(HttpClient client, String url, String token, Listener listener) throws Exception {
        return client.newWebSocketBuilder().header("Authorization", "Bearer " + token).connectTimeout(java.time.Duration.ofSeconds(5)).buildAsync(URI.create(url), listener).get(10, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        String token = System.getenv("TOKEN");
        String url = args.length > 0 ? args[0] : "ws://localhost:48080/api/v1/events/echo";
        var client = HttpClient.newHttpClient();
        var listener = new Listener();
        WebSocket socket = open(client, url, token, listener);
        socket.sendText("hello", true).join();
        if (!"echo:hello".equals(listener.message.get(5, TimeUnit.SECONDS))) throw new AssertionError("echo mismatch");
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        listener.closed.get(5, TimeUnit.SECONDS);
        var connections = new ArrayList<WebSocket>();
        try {
            for (int i = 0; i < 4; i++) connections.add(open(client, url, token, new Listener()));
            try {
                open(client, url, token, new Listener());
                throw new AssertionError("consumer connection cap bypassed");
            } catch (ExecutionException expected) {
                if (!(expected.getCause() instanceof WebSocketHandshakeException h) || h.getResponse().statusCode() != 429)
                    throw expected;
            }
        } finally {
            for (var connection : connections) connection.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
        System.out.println("PASS WebSocket authentication, echo and per-consumer connection cap");
    }

    static class Listener implements WebSocket.Listener {
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final CompletableFuture<String> message = new CompletableFuture<>();

        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            closed.complete(null);
            return null;
        }

        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            message.complete(data.toString());
            socket.request(1);
            return null;
        }

        public void onError(WebSocket socket, Throwable error) {
            message.completeExceptionally(error);
        }
    }
}
