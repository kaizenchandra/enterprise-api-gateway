# Capacity assumptions, not measurements

Assume 100k RPS, 1KiB request and 4KiB response: client-facing payload bandwidth = RPS*(request+response)*8 = 4.096Gbps,
plus upstream traffic, protocol/TLS overhead and headroom. 1M RPS is ten times this. At mean 50ms service time, Little's
law gives 5000 in-flight requests; long-lived connections add independently. JWT cache hits avoid JWKS I/O but still pay
signature verification CPU on every call. API keys add one Redis read; every admitted policy request adds one Lua round
trip. At 100k API-key RPS estimate 200k Redis operations/sec before administration/telemetry; this is demand, not
demonstrated capacity.

Pods required = ceil(peak RPS / measured sustainable RPS per pod / target utilization). Never assign an invented per-pod
number. JWT CPU cores = RPS * measured verification CPU seconds. Heap includes base JVM + route snapshot + pool
buffers + concurrency * bounded retained bytes + telemetry queues. Snapshot publication temporarily retains old and new
route graphs. Route lookup may be O(routes), validation O(routes²); 10k-route churn needs profiling and indexed conflict
detection.

For benchmark reporting specify hardware, JDK flags, CPU throttling, TLS topology, HTTP version, payload distribution,
keepalive, JWT/key distribution, warmup, GC and backend latency. Measure gateway overhead using direct-backend and
proxied matched workloads or gateway timing instrumentation; subtracting independent p99 values is invalid. Report
offered and achieved RPS, dropped iterations, rejections and retry amplification, not just successful latency. 100k
concurrent sockets require ingress/kernel/FD tuning and memory measurement. WebSocket soak tests are separate.

Multi-AZ costs include cross-zone Redis/DB traffic. Single-region multi-AZ is initial target. Active-passive requires
tested RTO/RPO and counter restore. Active-active needs regional entitlement allocations, data residency, configuration
ownership and bounded propagation; regional Redis does not establish a strict worldwide quota.
