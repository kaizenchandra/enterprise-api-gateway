# Final Java and HTTP test results

Full reactor `mvn -B verify` and CycloneDX generation completed on Java 21 against the final source. Actual Maven
reports: **49 tests, zero failures, errors or skips**. PostgreSQL/Redis containers ran; no container test was silently
omitted.

| Suite                                                | Tests | Failures | Errors | Skips | Seconds |
|------------------------------------------------------|------:|---------:|-------:|------:|--------:|
| io.acme.gateway.control.AdminKeyTest                 |     2 |        0 |      0 |     0 |   0.982 |
| io.acme.gateway.control.KeyRegistryIntegrationTest   |     4 |        0 |      0 |     0 |   7.258 |
| io.acme.gateway.data.AdmissionFailureTest            |     3 |        0 |      0 |     0 |   2.341 |
| io.acme.gateway.data.GatewayContextTest              |     1 |        0 |      0 |     0 |   4.233 |
| io.acme.gateway.data.GatewayWebSocketIntegrationTest |     1 |        0 |      0 |     0 |   4.421 |
| io.acme.gateway.data.JwtSecurityTest                 |     7 |        0 |      0 |     0 |   0.703 |
| io.acme.gateway.data.ProtocolGuardTest               |     4 |        0 |      0 |     0 |   0.039 |
| io.acme.gateway.data.RedisAdmissionTest              |     4 |        0 |      0 |     0 |   1.789 |
| io.acme.gateway.data.SnapshotCacheTest               |     4 |        0 |      0 |     0 |   0.189 |
| io.acme.gateway.example.BackendIdentityTest          |     6 |        0 |      0 |     0 |   0.556 |
| io.acme.gateway.example.BackendWebSocketTest         |     1 |        0 |      0 |     0 |   2.511 |
| io.acme.gateway.example.PaymentHttpTest              |     3 |        0 |      0 |     0 |    0.17 |
| io.acme.gateway.policy.RouteValidatorTest            |     9 |        0 |      0 |     0 |   0.092 |

The final source-built images then passed `demo.py`, `distributed_http_test.py`, all ten `acceptance.py --faults`
groups, the portal DOM/live HTTP test, telemetry credential/query canaries and collector outage, dashboard verification
and inspection of all six running jars. See [verification](verification.md) and `results/evidence.json` for source/image
attribution and log checksums.

Defects reproduced and corrected during continuation: payment `Mono<?>` flattened 409/503 to 200; stale response
Content-Length corrupted error responses; legacy JSON optional defaults broke after Jackson 3 migration; WebSocket
post-filters touched committed headers; upgraded-connection admission released too early; and Resilience4j silently
added a default 25-call bulkhead over the route concurrency policy. The initial 1,000-RPS benchmark saw 16 actual
BulkheadFullException/503 responses. The final benchmark returned zero HTTP failures, while still dropping offered
iterations. Raw pre-fix and final summaries are retained rather than reporting target performance as passed.

Decoder tests validate real RSA signatures and a local JWKS server. WebSocket tests open real sockets through the SCG
server and signed backend; key reconciliation tests use actual PostgreSQL/Redis, and quota tests use independent
concurrent Redis clients. These distinct boundaries supplement the full Compose HTTP tests.
