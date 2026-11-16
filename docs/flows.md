# Detailed flows

```mermaid
classDiagram
 Snapshot "1" *-- "many" RouteSpec
 RouteSpec --> RouteValidator
 Store --> Snapshot : publishes
 Configurations --> Snapshot : validates
 Admission --> RouteSpec : captured policy
 Admission --> Identity : verified claims
 class Snapshot { long version; Instant publishedAt; List~RouteSpec~ routes }
 class RouteSpec { String tenant; String path; Set scopes; int rate; long monthlyQuota }
```

JWT validation:

```mermaid
flowchart LR
 Token --> RS256[RS256 only]
 RS256 --> JWKS[Fixed trusted JWKS / cached key]
 JWKS --> Claims[Issuer / audience / expiry / nbf / type / tenant]
 Claims --> Scope[Route tenant AND scopes AND roles]
 Claims -->|invalid| Deny[401]
 Scope -->|not allowed| Forbidden[403]
```

API key validation:

```mermaid
flowchart LR
 Key[Header key] --> Parse[Bounded identifier + 256-bit secret]
 Parse --> Redis[Read live verifier from Redis]
 Redis --> Hash[Constant-time verifier comparison / expiry]
 Hash --> Identity[Trusted consumer / tenant / grants]
 Redis -->|missing or expired| Deny[401]
 Redis -->|unavailable| Closed[503]
```

Distributed rate limiting:

```mermaid
sequenceDiagram
 participant A as Gateway A
 participant B as Gateway B
 participant R as Authoritative Redis
 par Concurrent calls
 A->>R: EVAL bucket + quota same tenant slot
 B->>R: EVAL bucket + quota same tenant slot
 end
 R->>R: Serialize TIME/refill/check/consume
 R-->>A: Allowed
 R-->>B: Allowed or 429
```

Quota enforcement:

```mermaid
flowchart LR
 Clock[Redis UTC TIME] --> Period[Day / Gregorian month]
 Period --> Read[Read current-period counters]
 Read --> Check{Both limits allow?}
 Check -->|no| Reject[No counter changes / 429]
 Check -->|yes and bucket allows| Increment[Atomic admitted usage increment]
 Increment --> Dispatch[Local bulkhead then upstream]
 Dispatch --> Count[Count remains consumed on any outcome]
```

Endpoint selection:

```mermaid
flowchart LR
 Route[Validated route] --> Choice[Random endpoint / repeated entries weight]
 Choice --> DNS[Kubernetes Service DNS]
 DNS --> Ready[Ready endpoint set]
 Ready --> Pool[Reusable Netty connection]
```

Circuit breaker and retry:

```mermaid
stateDiagram-v2
 CLOSED --> OPEN : transport or HTTP status failure threshold
 OPEN --> HALF_OPEN : wait duration
 HALF_OPEN --> CLOSED : successful probes
 HALF_OPEN --> OPEN : probe failure
 note right of OPEN : Per route; safe reads may retry once within deadline
```

Configuration recovery:

```mermaid
flowchart LR
 Poll[Poll latest complete snapshot] --> Validate[Validate and compile all]
 Validate -->|valid| Replace[Atomic locator replacement / cache refresh]
 Validate -->|invalid| Last[Retain last valid snapshot]
 Poll -->|unavailable| Last
 Last --> Fresh{Less than five minutes since sync?}
 Fresh -->|yes| Continue[Continue existing configuration]
 Fresh -->|no| Fail[Fail closed]
 Cold[Cold start] --> Cache[Validate local snapshot with original verification time]
 Cache --> Fresh
 Cold --> Poll
```

Control/data trust separation:

```mermaid
flowchart TB
 Admin[Management token: gateway-admin] --> Control[Servlet/JDBC control plane]
 Client[API token: gateway] --> Data[Reactive Netty data plane]
 Control --> PG[(Durable snapshots)]
 Data -->|Internal control credential| Control
 Data --> Backend[Business authorization remains here]
```
