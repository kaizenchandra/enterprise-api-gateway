# Resilience and streaming

SCG uses a separate Resilience4j circuit breaker per route: a 50-call window, minimum 20 calls, 50% failure threshold,
and 10-second open wait. Transport failures and HTTP 500/502/503/504 count as failures. No fallback fabricates success.
See the acceptance evidence for actual opening/recovery checks; slow-call tuning and large concurrent half-open storms
require production workload validation.

Retries default to zero. A route may opt into one additional attempt only if all allowed methods are GET/HEAD. SCG
performs bounded exponential backoff (50ms initial, 200ms maximum, factor 2, jitter 0.5) for 502/503/504 or transport
exceptions within the single overall request deadline. POST retries are rejected at validation, including payment
routes. A maximum of two dispatches per admitted read bounds amplification; there is no fleet-wide adaptive retry
budget. Backend/client retries must be coordinated separately.

Immediate atomic concurrency admission avoids blocking event loops: per-route configured cap and 4096 active requests
per process. HTTP pools: up to 512 connections per upstream origin, 256 pending acquisitions, 500ms acquisition, 1s
connect, 30s idle and 5min lifetime. Netty response and overall route deadlines are bounded at <=30s. Resilience4j
TimeLimiter is disabled because the gateway owns the overall deadline. The implicit Spring Cloud Resilience4j bulkhead
is also disabled: admission is the sole owner of route/process limits. An actual load run exposed its independent
default cap of 25 calls; the context regression and subsequent image tests protect against reintroducing it. Saturated
admission returns 429; dependency/deadline failures return 503/504. All Lua-admitted calls count even when local
admission later rejects.

WebSockets use maintained SCG ws routing, signed handshake identity, a four-connection per-consumer per-pod cap,
process/route admission and a native channel close listener that retains admission until physical closure. Lifetime is
capped at 30s. During deployment, clients must reconnect with backoff after closure; rolling deployments cannot migrate
live TCP sockets. Indefinite WebSockets and message-level authorization are outside this demo.

HTTP responses stream under backpressure. A finite SSE example emits ten events over one second and is subject to the
overall deadline. Writes require Content-Length <=1MiB; chunked writes return 411 and oversized bodies 413. Indefinite
SSE, large uploads and media transfer should use separately designed routes or pre-signed object-store URLs.

Demo orders/payments/inventory state is memory-only. Payment idempotency rejects a changed body with the same
tenant/key; it is not a durable financial ledger across restarts or replicas.
