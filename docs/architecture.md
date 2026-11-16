# Architecture and requirements

Target C: independent management/control and reactive request/data deployments. A static gateway is cheapest and has no
propagation lag but needs redeployment. Dynamic configuration adds change agility and eventual convergence. Control/data
separation isolates JDBC and administration, at the cost of protocol and freshness management. Multi-region adds
latency, ownership, residency and quota allocation complexity; defer until regional demand warrants it.

Security invariants: no protected upstream dispatch without verified credentials and route permission; no caller-owned
tenant or identity; no arbitrary upstream URL; no partial configuration activation; bounded admission and deadlines; no
unsafe retries; administration is a separate audience and process. Resource ownership remains a backend responsibility.

Initial SLO targets are 99.99% availability and <10ms p99 added overhead for an explicitly specified small-payload
workload. Neither is measured. Target 100k RPS initially, million RPS via measured scale-out, 100k cluster connections,
10k routes and tenants, 100k consumers. Current route lookup and conflict validation must be profiled before claiming
that scale.

```mermaid
flowchart LR
 C[Clients] --> L[TLS ingress]
 L --> D[Stateless reactive data planes]
 D --> B[Orders / Payments / Inventory]
 D --> R[Redis atomic admission / API key registry]
 D --> I[IdP JWKS]
 A[Platform administrator] --> P[Control plane and portal]
 P --> DB[(PostgreSQL registry / immutable versions / audit)]
 P --> R
 D -->|poll full versioned snapshot| P
 D --> O[Micrometer / OTel / Prometheus]
```

The administrative connection is authenticated by a separate fixed shared secret in the local baseline; production
requires mTLS, network isolation and secret rotation. API administration tokens require `gateway-admin` audience and
`gateway.read`/`gateway.write` scopes. This implements platform-wide operators, not delegated tenant administrators.

```mermaid
sequenceDiagram
 participant C as Caller
 participant G as Gateway
 participant I as JWKS / Redis keys
 participant R as Redis Lua
 participant B as Backend
 C->>G: Versioned route + credential
 G->>G: Protocol and immutable route lookup
 G->>I: Verify trusted JWT or key verifier
 G->>G: Verify tenant, scopes, roles
 G->>R: Atomic token and tenant/API quota admission
 R-->>G: Allow or retry delay
 G->>G: Try immediate local concurrency admission
 G->>B: Signed verified identity + trace context
 B-->>C: Stream response through gateway under deadline
```

```mermaid
sequenceDiagram
 participant A as Admin
 participant P as Control plane
 participant DB as PostgreSQL
 participant G as Each Gateway
 A->>P: Save draft with If-Match
 P->>P: Validate schema, targets and conflicts
 P->>DB: Serialize publish, persist immutable version
 loop Every 3 seconds
 G->>P: Fetch latest full snapshot
 G->>G: Validate all routes, compile entire snapshot
 G->>G: Atomic locator reference replacement, refresh SCG cache
 end
 A->>P: Roll back to old content
 P->>DB: Publish old content as new increasing version
```

Snapshot replacement never exposes a partially compiled list. SCG route cache refresh is asynchronous; existing
exchanges retain the prior immutable Route. Polling and refresh convergence are eventual, not a distributed atomic
cutover. Cache freshness tracks successful validated synchronization on each pod. Heartbeats expose per-pod version/age,
but do not establish a distributed atomic activation acknowledgement. Fleet convergence deadlines and alerts remain
operator responsibilities.

```mermaid
flowchart TB
 subgraph Kubernetes regional multi-AZ
 Ingress[TLS ingress] --> DP[Gateway replicas / HPA / PDB]
 DP --> S[ClusterIP backend services]
 DP --> CP[Control plane replicas]
 CP --> PG[(HA PostgreSQL)]
 DP --> Redis[(Authoritative Redis shard)]
 CP --> Redis
 end
```

Kubernetes service DNS is the recommended upstream strategy: endpoint health and removal belong to Services and
readiness. Exact allowlisted service origins prevent arbitrary metadata access; enforce egress policies as defense in
depth. Random endpoint selection supports equal weight; repeated entries express coarse weight. There are no active
health probes or endpoint draining state in the gateway, or a discovery registry. Literal X-Release/X-API-Version
predicates support controlled header canaries; Kubernetes readiness/draining owns endpoint health.

Configuration hot path stays in memory. Reactive Redis/JWKS operations are asynchronous. Control plane uses blocking
JDBC only in its servlet runtime. Lua serializes admission at a tenant hash slot: correct single-primary accounting but
large tenants can create hot shards. Route-level circuit breakers keep unrelated APIs separate. Local concurrency is a
bulkhead, not a global quota.
