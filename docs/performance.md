# Measured performance and benchmark protocol

Final local execution: 2026-11-15. Source `04e40a21ba2ad54ba5b66a1d52e9a854f99a1c2f2201fc31914662929392d9b8`. These
measurements do not certify the requested enterprise throughput or latency SLO.

Workload: HTTP/1.1, no TLS, loopback Docker on macOS arm64/OrbStack; small JSON order-service response; fixed trusted
JWT signing-key cache, JWT verification, gateway RSA identity signing, backend identity verification, atomic Redis
token/quota admission, route breaker/deadline/concurrency, tracing sampled at 0.1, Prometheus and collector/Grafana
running. One k6 process, 15 seconds per offered rate, 32 preallocated VUs and maximum 128; each application JVM uses
ActiveProcessorCount=2 (this does not impose a two-core container CPU quota). Runs followed the fault suite, including
gateway restart; no steady-state JVM warmup guarantee is claimed.

| Offered RPS | Achieved RPS | Completed HTTP | HTTP errors | Dropped iterations | E2E p50 / p95 / p99 ms   | Policy p50 / p95 / p99 ms |
|------------:|-------------:|---------------:|------------:|-------------------:|--------------------------|---------------------------|
|         100 |        97.80 |           1468 |           0 |                 33 | 20.41 / 506.50 / 721.12  | 6.78 / 268.38 / 377.60    |
|        1000 |       672.56 |          10255 |           0 |               4746 | 162.86 / 303.00 / 390.87 | 147.65 / 262.33 / 324.94  |

Both runs passed the strict zero HTTP error threshold. **Neither sustained its offered rate**: dropped iterations are
generator work that never reached the gateway and must not be counted as successes. Policy timing is the measured
admission/signing stage, not complete gateway processing overhead. E2E includes the backend. No matched direct-backend
measurement, TLS, HTTP/2, cold JWKS miss series, WebSocket connection load or true full gateway-overhead distribution
was measured. Do not subtract unrelated percentile measurements to infer overhead.

## Evidence and diagnosed bottleneck

An earlier run on the immediately preceding images produced 16 actual 503s at 1,000 offered RPS. Sanitized gateway
diagnostics classified all 16 as `BulkheadFullException`. Spring Cloud automatically installed a separate Resilience4j
bulkhead (25 concurrent calls) in addition to explicit route admission (512 for this benchmark). That implicit bulkhead
was disabled using its documented property; the explicit per-route/process admission and bounded Netty pools remain. A
Spring context regression verifies the extra provider is absent. Final source images passed the entire HTTP/fault suite
again, and the subsequent benchmark observed zero failed HTTP requests. `benchmark-before-bulkhead-fix.json` and
`k6-1000-before-bulkhead-fix.json` retain the failed measurement.

Raw final data: `results/k6-{100,1000}.json/.log`, `benchmark-summary.json`, before/after Prometheus snapshots,
`benchmark-*-resources.json` and Redis commandstats. CPU samples show substantial gateway/backend CPU activity, while
the measured Redis EVALSHA execution averages over each run were about 80.52µs and 29.10µs respectively. These Redis
figures exclude network and scheduling delay. The observations suggest investigating gateway/backend CPU, JVM warmup and
host scheduling before treating Lua execution as the dominant bottleneck; this is an inference, not a completed profiler
attribution. Container RSS and Java heap differ because native Netty/TLS/thread buffers and code cache consume memory
outside heap.

No event-loop CPU profiles were captured, and no hardware capacity extrapolation is certified. Pending work: isolated
hardware, sustained warmup/runs, matched direct/proxy workload, CPU/JFR profiles, GC/pool/Redis network latency deltas,
controlled payload distributions, TLS topology, HTTP/2, multiple generators, route-count/cardinality scaling, WebSocket
connection tests, failed-upstream/config-update load and multi-pod scale curves.

## Reproduce

```sh
# Start/seed the platform using README instructions; install k6 separately.
TRACING_ENABLED=true docker compose --env-file docker/.env -f docker/compose.yml --profile monitoring up --no-build -d --wait --wait-timeout 600
python3 scripts/benchmark.py
```

The script provisions a distinct high-quota benchmark route without weakening protected production routes, records
resource snapshots, preserves k6 failures and exits nonzero for HTTP errors. `benchmarks/gateway.js` accepts RPS=1000,
10000, 100000, 500000 and 1000000 (or another offered rate). They are generator settings, not measured supported
capacity. Use separately isolated benchmark configurations for routing-only, JWT-only, API-key, rate/quota and
full-stack comparisons; no anonymous bypass profile is enabled in the production application.

The 100k/million-RPS targets, p99<10ms gateway overhead, 100k connections and 99.99% availability remain
**unverified/unmet by these local measurements**. Capacity formulas in [capacity-planning](capacity-planning.md) are
estimates with explicit assumptions.
