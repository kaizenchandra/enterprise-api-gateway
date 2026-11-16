````
# MASTER PROMPT: Design and Develop a Production-Grade Enterprise API Gateway with Authentication, Authorization, Quotas, Rate Limiting, and Dynamic Routing Using Java 21 and Spring Boot

## 1. ROLE AND ENGINEERING RESPONSIBILITIES

Act as a **Principal Software Engineer, Distinguished Distributed Systems Architect, Enterprise Solution Architect, Java 21 Expert, Spring Boot and Spring Cloud Architect, API Security Architect, Cloud-Native Platform Engineer, and Site Reliability Engineer** with 15+ years of experience designing high-throughput enterprise API platforms comparable in architectural capabilities to Kong, Apache APISIX, Envoy Gateway, AWS API Gateway, and enterprise API management solutions.

Your task is to **design, develop, test, security-harden, benchmark, document, and deploy an end-to-end Enterprise API Gateway Platform** using Java 21, Spring Boot, and Spring Cloud Gateway.

Treat this as a real enterprise infrastructure engineering project, not merely a conceptual system design exercise.

### Primary responsibilities

1. Functional and non-functional requirements
2. High-Level Design (HLD)
3. Low-Level Design (LLD)
4. API Gateway architecture
5. Control plane and data plane separation
6. Dynamic API routing
7. Route discovery and configuration
8. OAuth2/OIDC authentication
9. JWT and API key validation
10. Centralized gateway authorization
11. Distributed rate limiting
12. Tenant and consumer quotas
13. Traffic shaping and admission control
14. Load balancing
15. Circuit breaker, retry, timeout, and bulkhead
16. Header and request/response transformation
17. API versioning
18. WebSocket and streaming proxy support
19. API lifecycle and administration
20. Service-to-service security
21. Multi-tenant isolation
22. Security threat modeling
23. Observability and distributed tracing
24. High availability and scalability
25. Failure recovery
26. Automated concurrency/security/integration testing
27. Performance benchmarking
28. Docker and Kubernetes deployment
29. Functional administrative portal
30. Complete source code and documentation

**Do not stop after HLD, LLD, pseudocode, configuration examples, or illustrative snippets. Deliver a runnable, secure, maintainable, tested API Gateway platform with working policies, administrative APIs, backend demonstration services, infrastructure, and operational documentation.**

### Mandatory engineering principles

- Use Java 21 and a verified compatible Spring Boot and Spring Cloud Gateway release.
- Verify Spring Boot, Spring Framework, Spring Security, Reactor Netty, and Spring Cloud compatibility using official documentation.
- Follow repository instructions, dependencies, architecture, and coding conventions.
- Apply SOLID, DDD, Clean Architecture, and Hexagonal Architecture pragmatically.
- Prefer Spring Cloud Gateway's maintained routing and filter infrastructure over implementing a reverse proxy from scratch.
- Separate API management/control-plane responsibilities from request-processing/data-plane responsibilities.
- Do not perform blocking JDBC, Redis, or HTTP operations directly on Reactor Netty event-loop threads.
- Use bounded concurrency, retries, timeouts, connection pools, memory, and queues.
- Treat authentication and authorization as separate responsibilities.
- Never trust unverified client-supplied identity, tenant IDs, scopes, or forwarded headers.
- Enforce distributed quota rules using atomic coordination.
- Never use instance-local counters as the sole mechanism for globally enforced limits.
- Do not retry unsafe non-idempotent requests without a verified idempotency contract.
- Prevent request smuggling, SSRF, open proxy abuse, route hijacking, and authorization bypass.
- Never expose secrets, API keys, bearer tokens, or sensitive payloads in logs.
- Never fabricate test results, benchmarks, or production-readiness claims.
- Explain important architecture decisions, consistency guarantees, and residual risks.

---

# PART A — PRIMARY OBJECTIVE

## 2. BUILD AN ENTERPRISE API GATEWAY

Build a horizontally scalable API Gateway that acts as the secure entry point for multiple enterprise backend services.

The platform must support:

- Centralized API ingress
- Request authentication
- Authorization policy enforcement
- API key management
- OAuth2/JWT validation
- Dynamic route management
- Path/host/method-based routing
- Consumer-level rate limiting
- Tenant-level quotas
- Burst traffic control
- Request validation
- Request/response transformation
- Load balancing
- Service health-aware routing
- Circuit breakers
- Bounded retries
- Timeouts
- WebSocket routing
- Distributed tracing
- API usage analytics
- Administrative APIs
- Secure configuration updates

### Example architecture

Client applications access:

- Order Service
- Payment Service
- Inventory Service
- Customer Service

through:

`Client → Enterprise API Gateway → Backend Microservices`

### Example end-to-end request

A customer invokes:

`POST /api/v1/orders`

The Gateway must:

1. Terminate or receive trusted TLS traffic.
2. Validate request size and protocol properties.
3. Resolve the target API route.
4. Validate OAuth2/JWT or the configured authentication method.
5. Validate the intended issuer, audience, expiration, and token claims.
6. Determine the authenticated consumer and verified tenant.
7. Evaluate route-level authorization.
8. Check relevant rate limits.
9. Check tenant API quotas.
10. Enforce burst and concurrency limits.
11. Select a healthy upstream endpoint.
12. Apply configured request transformations.
13. Propagate trusted identity and trace context.
14. Forward the request within a bounded deadline.
15. Classify upstream errors.
16. Apply only safe retry and resilience policies.
17. Return the upstream response.
18. Add appropriate response headers.
19. Emit metrics, logs, and distributed traces.
20. Record usage asynchronously where justified.

### Fundamental invariants

- Unauthorized requests never reach protected upstream operations through supported Gateway paths.
- Consumer identity cannot be spoofed through untrusted headers.
- Tenant quota enforcement follows its documented distributed consistency model.
- A route configuration update cannot expose an unintended internal service.
- Gateway failure does not create unbounded downstream traffic or memory usage.
- Unsafe retries do not duplicate protected business operations.
- Gateway administration is separate from ordinary API consumption.
- Backend services retain responsibility for business/resource-level authorization.

---

# PART B — NON-FUNCTIONAL REQUIREMENTS

## 3. TARGET SCALE AND PERFORMANCE

Treat these as design targets requiring verification.

| Dimension | Design Target |
|---|---|
| Aggregate Gateway throughput | 100,000 RPS initially; scale-out design toward 1 million+ RPS |
| Gateway processing latency | p99 below 10 ms for a specified minimal-policy workload |
| Availability | 99.99% design target |
| Concurrent connections | 100,000+ across the initial cluster |
| Registered APIs | 10,000+ |
| API consumers | 100,000+ |
| Active tenants | 10,000+ |
| Authentication | OAuth2/OIDC, JWT, API key |
| Quotas | Per consumer, API, tenant, and plan |
| Routing | Dynamic and versioned |
| Deployment | Multi-AZ Kubernetes |
| Observability | Metrics, logs, distributed traces |

Clarify:

- Gateway-only overhead versus end-to-end latency
- Authentication cache hits versus misses
- Local versus distributed rate-limit decisions
- Buffered versus streamed payloads
- HTTP/1.1 versus HTTP/2
- TLS termination topology
- WebSocket connection load
- Request and response payload distributions

Do not claim million-RPS throughput without measured evidence.

---

# PART C — HIGH-LEVEL ARCHITECTURE

## 4. COMPARE ARCHITECTURE OPTIONS

### Option A — Stateless Gateway with Static Configuration

Routes are configured at deployment time.

### Option B — Gateway with Dynamic Configuration

Gateway instances retrieve versioned routes and policies from an administrative control plane.

### Option C — Control Plane + Data Plane

Control Plane manages APIs, consumers, authentication policies, quotas, routes, and configuration distribution.

Data Plane handles live client traffic using validated immutable configuration snapshots.

### Option D — Multi-Region Enterprise Gateway

Regional data planes with centralized or regionally partitioned control and policy management.

Evaluate:

- Throughput
- Request latency
- Availability
- Configuration consistency
- Operational complexity
- Security boundaries
- Multi-region deployment
- Cost

**Recommend Option C as the target architecture, with a minimal but functional initial implementation.**

---

## 5. REFERENCE COMPONENTS

Design:

1. API Gateway Data Plane
2. Gateway Control Plane
3. Route Registry
4. Route Configuration Store
5. Authentication Filter
6. Authorization Policy Engine
7. API Key Manager
8. Rate Limiting Engine
9. Quota Manager
10. Consumer/Tenant Resolver
11. Load Balancer
12. Circuit Breaker
13. Retry Policy Engine
14. Timeout Manager
15. Request/Response Transformation
16. Configuration Distributor
17. Usage Metering
18. Audit Logger
19. Redis Cluster
20. PostgreSQL
21. Identity Provider
22. OpenTelemetry Collector
23. Prometheus/Grafana

Provide Mermaid diagrams for:

- Overall architecture
- Control/data plane separation
- Authenticated request
- JWT validation
- API key validation
- Distributed rate limiting
- Quota enforcement
- Dynamic route changes
- Load balancing
- Circuit breaker and retry
- Configuration recovery
- Kubernetes deployment

---

# PART D — CONTROL PLANE

## 6. API MANAGEMENT

Implement control-plane capabilities:

- Register API
- Update API
- Delete/deactivate API
- Define route
- Update route
- Configure authentication
- Configure authorization
- Configure quotas
- Configure rate limits
- Configure upstream targets
- Configure timeouts
- Configure retries
- Configure circuit breakers
- Publish configuration
- Roll back configuration
- Inspect deployment status
- Audit configuration changes

Use PostgreSQL for durable configuration management.

Do not place administrative database operations on the Gateway request hot path.

---

## 7. ROUTE CONFIGURATION MODEL

Define:

- Route ID
- API ID
- Tenant scope
- Host predicate
- Path predicate
- HTTP methods
- Upstream target
- Authentication policy
- Authorization policy
- Rate-limit policy
- Quota policy
- Timeout policy
- Retry policy
- Transformation policy
- Route priority
- Configuration version
- Enabled/disabled status

Validate route conflicts and precedence deterministically.

### Important rule

An invalid or partially updated route configuration must never be activated in the Gateway.

---

# PART E — DYNAMIC CONFIGURATION

## 8. CONFIGURATION PROPAGATION

Evaluate:

- Polling
- Redis Pub/Sub
- Kafka configuration events
- Server-Sent Events
- Control-plane push
- Kubernetes configuration updates

Implement a safe baseline.

Required properties:

- Versioned configuration
- Immutable snapshots
- Validation before activation
- Atomic snapshot replacement
- Idempotent updates
- Rollback
- Missing-version recovery
- Startup synchronization
- Stale-config detection
- Audit history

### Critical scenario

1. Control Plane publishes configuration version 42.
2. Ten Gateway instances receive it.
3. Two instances miss the update.
4. Version 43 is published.
5. All instances must eventually reconcile to the correct validated configuration.

Do not rely solely on a lossy notification mechanism without snapshot reconciliation.

---

## 9. CONFIGURATION SECURITY

Protect:

- Route updates
- Upstream addresses
- Authentication policy changes
- Quota changes
- TLS settings
- API key policies
- Administrative commands

Use:

- RBAC
- Approval workflow where justified
- Audit events
- Immutable version history
- Validation
- Least privilege

Prevent route changes from turning the Gateway into an unrestricted open proxy.

---

# PART F — DYNAMIC ROUTING

## 10. ROUTING ENGINE

Implement:

- Path-based routing
- Host-based routing
- HTTP method matching
- Header predicates where justified
- Weighted routing
- Canary routing
- API version routing
- Route priority
- Route enable/disable

Use Spring Cloud Gateway route definitions and filters where supported.

Do not implement an unnecessary custom HTTP proxy engine.

---

## 11. UPSTREAM SERVICE DISCOVERY

Compare:

- Static upstream targets
- Kubernetes DNS
- Spring Cloud LoadBalancer
- Service discovery registry
- Endpoint-based configuration

Choose a strategy appropriate for Kubernetes.

Do not introduce Eureka or Consul merely because the project uses microservices.

Support:

- Multiple upstream instances
- Health-aware selection
- Connection reuse
- Failover
- Endpoint draining

---

# PART G — AUTHENTICATION

## 12. OAUTH2 AND OPENID CONNECT

Integrate with an established Identity Provider.

Use Spring Security OAuth2 Resource Server support.

Validate:

- JWT issuer
- Signature
- Allowed algorithm
- Audience
- Expiration
- Not-before
- Token type
- Scope
- Required claims

Support OIDC identity information where applicable.

Do not implement an Authorization Server inside the Gateway by default.

Keep token issuance and resource access enforcement logically separate.

---

## 13. JWT VALIDATION

Implement:

- Trusted issuer configuration
- JWKS discovery
- Signing-key caching
- Key rotation
- Clock skew allowance
- Token expiration
- Audience restrictions
- Scope mapping
- Authentication failure responses

Do not accept arbitrary issuer/JWKS URLs from untrusted requests.

Avoid JWT algorithm confusion.

### Failure behavior

Explain what happens when:

- Identity Provider is unavailable
- JWKS endpoint fails
- Signing key rotates
- Token has unknown `kid`
- Token expires during an active request
- Token is revoked

Define a revocation freshness model.

Do not assume JWT signature validation proves that an account remains active or still has permission for every resource.

---

# PART H — API KEY AUTHENTICATION

## 14. API KEY LIFECYCLE

Implement:

- API key creation
- API key rotation
- API key revocation
- API key expiration
- API key scopes
- Consumer association
- Usage monitoring
- Key status inspection

Generate cryptographically secure keys.

Store a key identifier and a secure verifier, not unrestricted plaintext API keys.

Display the secret only during authorized initial issuance.

### Security

- Never log raw API keys.
- Avoid API keys in query strings.
- Use TLS.
- Enforce scoped access.
- Support immediate or bounded revocation according to the cache model.
- Rate-limit invalid-key attempts.
- Prevent key enumeration.

Do not treat API keys as equivalent to interactive user authentication.

---

# PART I — AUTHORIZATION

## 15. GATEWAY AUTHORIZATION

Implement:

- Required authentication
- Scope-based authorization
- Role-based authorization
- Client/application permissions
- Tenant-level authorization
- Route-level authorization
- Optional external PDP integration

Define policy precedence.

Use default-deny behavior for protected routes.

### Critical distinction

Gateway authorization can determine whether a caller is permitted to invoke an API category.

Backend services must still enforce:

- Resource ownership
- Business authorization
- Order/customer access
- Payment permissions
- Tenant data isolation

Do not claim the Gateway can replace all downstream authorization.

---

## 16. IDENTITY PROPAGATION

Define how verified identity is propagated to upstream services.

Evaluate:

- Forward original validated bearer token
- Token exchange
- Gateway-signed identity context
- mTLS-backed internal identity assertions

Remove or overwrite untrusted incoming identity headers before forwarding.

Never trust client-provided:

- `X-User-ID`
- `X-Tenant-ID`
- `X-Roles`
- `X-Authenticated-User`

Use established identity propagation protocols.

---

# PART J — DISTRIBUTED RATE LIMITING

## 17. RATE LIMITING ALGORITHMS

Compare:

1. Fixed Window Counter
2. Sliding Window Counter
3. Sliding Window Log
4. Token Bucket
5. Leaky Bucket
6. GCRA

Evaluate:

- Accuracy
- Burst behavior
- Memory
- Throughput
- Redis operations
- Consistency
- Operational cost

Select a default algorithm and justify it.

Prefer Token Bucket or another justified atomic algorithm for the initial implementation.

---

## 18. RATE LIMIT DIMENSIONS

Support:

- Per-IP
- Per-user
- Per-API-key
- Per-client
- Per-tenant
- Per-route
- Global
- Subscription-plan-based

Example policies:

| Scope | Example Limit |
|---|---|
| Free consumer | 100 requests/minute |
| Standard consumer | 1,000 requests/minute |
| Enterprise tenant | 100,000 requests/minute |
| Payment API | 20 requests/second per authorized client |
| Global protection | Configurable aggregate rate |

Treat all values as illustrative.

Define whether applicable limits are enforced together.

---

## 19. REDIS ATOMIC RATE LIMITING

Implement atomic distributed enforcement using:

- Redis Lua scripting
- Supported Redis Functions
- Equivalent atomic operations

Address:

- Concurrent Gateway instances
- Token refill
- Bucket capacity
- Retry-after
- TTL
- Redis Cluster
- Hot keys
- Time source
- Failure handling
- Replica failover

Do not claim strict global enforcement if the design uses local quota leases or weakly coordinated regional counters.

---

# PART K — TENANT QUOTAS

## 20. QUOTA VS RATE LIMIT

Explain the difference.

**Rate limiting** controls request frequency and burst traffic.

**Quota management** controls total usage within a billing or administrative period.

Example:

- 100 requests/second
- 1 million requests/month

These are separate constraints.

---

## 21. QUOTA MANAGEMENT

Implement:

- Daily quotas
- Monthly quotas
- Per-API quotas
- Per-consumer quotas
- Per-tenant quotas
- Subscription-plan entitlements
- Usage inspection
- Quota reset
- Administrative adjustment

Define:

- Time zone and billing-period boundary
- Usage reservation
- Commit/refund semantics
- Concurrent quota consumption
- Period rollover
- Over-limit behavior
- Cache consistency
- Auditability

### Important correctness question

When should quota consumption occur?

Evaluate:

1. Before upstream request
2. After successful upstream response
3. Reservation before dispatch, commit after outcome
4. All admitted requests count regardless of upstream result

Choose an explicit policy.

Do not assume failed upstream calls should always be free.

---

## 22. QUOTA CONSISTENCY

For strict hard quotas, use a coordination mechanism that prevents concurrent overspending under the defined deployment model.

For approximate quotas, document permissible overshoot.

Evaluate:

- PostgreSQL atomic counters
- Redis counters
- Quota leasing
- Periodic reconciliation

Do not use eventually consistent usage analytics alone to enforce strict paid quotas.

---

# PART L — TRAFFIC SHAPING AND ADMISSION CONTROL

## 23. TRAFFIC PROTECTION

Implement or evaluate:

- Rate limiting
- Concurrency limiting
- Request size limits
- Connection limits
- Load shedding
- Burst control
- Priority admission
- Per-tenant fairness
- WebSocket connection admission

Avoid treating rate limiting alone as sufficient overload protection.

Support HTTP 429 responses with appropriate retry information.

Define behavior when Redis or quota coordination becomes unavailable.

---

# PART M — RESILIENCE

## 24. CIRCUIT BREAKER

Integrate Resilience4j or a verified maintained resilience solution.

Support:

- CLOSED
- OPEN
- HALF_OPEN
- Failure rate thresholds
- Slow-call thresholds
- Half-open probes
- Dependency-specific policies
- Metrics

Avoid a single shared Circuit Breaker for unrelated upstream APIs.

---

## 25. RETRY

Implement:

- Retryable exception classification
- Retryable HTTP status classification
- Exponential backoff
- Jitter
- Maximum attempts
- Deadline-aware retries
- Retry budgets

### Critical payment example

A payment request times out after the upstream service has already processed it.

An automatic retry could create a duplicate financial effect.

Do not retry unsafe operations without a verified idempotency mechanism.

Avoid nested retry amplification across Gateway and backend services.

---

## 26. BULKHEAD AND TIMEOUT

Implement:

- Bounded concurrent upstream calls
- Connection pool limits
- Pending acquisition limits
- Response timeout
- Connection timeout
- Overall request deadline
- Graceful rejection

Prefer non-blocking primitives for the reactive Gateway data plane.

Do not use blocking semaphore acquisition on a Reactor Netty event loop.

---

# PART N — REQUEST AND RESPONSE TRANSFORMATION

## 27. TRANSFORMATION POLICIES

Support where justified:

- Add/remove headers
- Rewrite paths
- Normalize request headers
- Response header enrichment
- CORS
- Correlation IDs
- API version headers
- Error normalization

Avoid unnecessary body buffering.

Do not implement unrestricted arbitrary scripting in route configuration.

Protect against:

- Header injection
- Header spoofing
- HTTP request smuggling
- Content-length/transfer-encoding ambiguity
- Sensitive response-header leakage

---

# PART O — API VERSIONING

## 28. VERSION MANAGEMENT

Support:

- URL-based versioning
- Header-based versioning
- Versioned route configuration
- Deprecation policy
- Sunset policy
- Backward-compatible migration

Provide a controlled example:

`/api/v1/orders`

`/api/v2/orders`

Define migration and routing behavior.

Do not rewrite arbitrary version headers without an explicit contract.

---

# PART P — WEBSOCKET AND STREAMING

## 29. WEBSOCKET SUPPORT

Implement WebSocket proxy routing where supported.

Address:

- Authentication during handshake
- Authorization
- Connection limits
- Idle timeout
- Ping/pong
- Long-lived connections
- Graceful shutdown
- Connection draining
- Reconnection
- Per-consumer admission

Do not treat a successful WebSocket handshake as permanent authorization for every possible application message.

---

## 30. STREAMING AND LARGE PAYLOADS

Support efficient proxying for appropriate streaming workloads.

Avoid buffering entire large responses or file uploads in Gateway memory.

Use bounded buffering and upstream/client backpressure.

Explain when media/file transfer should bypass the Gateway using pre-signed object storage URLs.

---

# PART Q — SECURITY THREAT MODEL

## 31. ENTERPRISE API GATEWAY SECURITY

Perform STRIDE-based threat modeling.

Address:

1. Authentication bypass
2. Authorization bypass
3. API key theft
4. JWT signature manipulation
5. JWT issuer/audience confusion
6. Open proxy abuse
7. SSRF
8. Dynamic route poisoning
9. Header spoofing
10. X-Forwarded-For spoofing
11. Host header injection
12. HTTP request smuggling
13. Response splitting
14. Path normalization inconsistencies
15. Encoded path traversal
16. CORS misconfiguration
17. CSRF where applicable
18. TLS misconfiguration
19. WebSocket authentication bypass
20. Denial of service
21. Tenant quota bypass
22. Redis failure exploitation
23. Administrative privilege escalation
24. Sensitive logging
25. Upstream service impersonation

For each provide:

- Attack mechanism
- Security impact
- Prevention
- Detection
- Automated test strategy
- Residual risk

---

## 32. TRUSTED PROXY CONFIGURATION

Define the trust boundary for forwarded headers.

Only accept `Forwarded`, `X-Forwarded-For`, and related headers from explicitly trusted proxies.

Strip or sanitize untrusted forwarded metadata.

Configure trusted proxy ranges and application/framework behavior appropriately.

Never derive client IP blindly from the first untrusted header value.

---

# PART R — MULTI-TENANT GATEWAY

## 33. TENANT ISOLATION

Support:

- Tenant-specific routes
- Tenant-specific policies
- Tenant-specific quotas
- Tenant-specific authentication requirements
- Tenant administration
- Tenant usage reports

Derive tenant identity from verified authentication and membership.

Do not trust arbitrary `X-Tenant-ID` values.

Prevent tenant administrators from modifying another tenant's routes or policies.

---

# PART S — DATABASE DESIGN

## 34. CONTROL-PLANE POSTGRESQL SCHEMA

Design tables for:

- api_definitions
- route_definitions
- upstream_services
- upstream_endpoints
- api_consumers
- api_keys
- auth_policies
- authorization_policies
- rate_limit_policies
- quota_policies
- tenant_plans
- configuration_versions
- configuration_publications
- usage_records
- audit_events

Create only tables required by the implemented scope.

Define:

- Primary keys
- Foreign keys
- Unique constraints
- Optimistic locking
- Versioning
- Audit timestamps
- Authorization scope
- Retention
- Indexes

Implement Flyway migrations.

Do not query PostgreSQL synchronously for every proxied request.

---

# PART T — OBSERVABILITY

## 35. LOGS, METRICS, AND TRACES

Implement:

- OpenTelemetry
- Micrometer
- Structured JSON logs
- Prometheus
- Grafana
- Distributed tracing

Capture:

- Gateway request rate
- Authentication success/failure
- Authorization denials
- API key validation failures
- Rate-limit rejections
- Quota exhaustion
- Route lookup failures
- Upstream latency
- Upstream error rate
- Circuit breaker state
- Retry count
- Active connections
- WebSocket connections
- Configuration version
- Configuration propagation lag
- Redis latency
- Connection pool saturation

Avoid user IDs, raw API keys, request IDs, and unrestricted URLs as high-cardinality metric labels.

---

## 36. DISTRIBUTED TRACING

Support W3C Trace Context.

Propagate trace context through:

`Client → API Gateway → Backend Services`

Add meaningful spans for:

- Authentication
- Authorization
- Rate-limit evaluation
- Quota evaluation
- Routing
- Upstream connection
- Upstream response

Do not create unnecessary spans for every simple internal method.

Ensure errors are correlated across the Gateway and backend services.

---

# PART U — HIGH AVAILABILITY

## 37. GATEWAY CLUSTER DESIGN

Deploy multiple stateless Gateway instances behind a load balancer.

Support:

- Horizontal scaling
- Configuration synchronization
- Connection draining
- Rolling upgrades
- Regional failover
- Redis failover
- Control-plane outage
- Dependency failure

### Critical principle

**A Control Plane outage should not necessarily stop already configured Gateway data planes from routing traffic.**

Support continuing with the last known valid configuration snapshot, subject to explicit security freshness and revocation policies.

Do not continue accepting stale authorization policies indefinitely without a defined risk model.

---

# PART V — CAPACITY PLANNING

## 38. LARGE-SCALE GATEWAY SIZING

Calculate:

1. Requests per second
2. Concurrent connections
3. Average request size
4. Average response size
5. Network bandwidth
6. TLS CPU overhead
7. JWT verification cost
8. Redis operations/sec
9. Quota evaluation cost
10. Route lookup complexity
11. Upstream connections
12. Gateway memory
13. Worker/event-loop utilization
14. Per-route hot spots
15. Configuration memory
16. Metrics cardinality
17. Multi-region overhead

Use explicit formulas and assumptions.

Do not invent measured hardware capacity.

---

# PART W — JAVA 21 + SPRING BOOT STACK

## 39. TECHNOLOGY SELECTION

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot, compatible stable release |
| Gateway | Spring Cloud Gateway |
| Runtime | Reactive Netty/WebFlux Gateway variant, unless another supported variant is justified |
| Security | Spring Security |
| Authentication | OAuth2/OIDC Resource Server |
| Identity provider | Keycloak or another standards-based provider |
| API keys | Secure key registry |
| Rate limiting | Redis atomic operations/Lua |
| Quotas | Redis/PostgreSQL as justified |
| Configuration | PostgreSQL + versioned distribution |
| Load balancing | Spring Cloud LoadBalancer / Kubernetes |
| Resilience | Resilience4j integration |
| Metrics | Micrometer |
| Tracing | OpenTelemetry |
| Testing | JUnit 5, Mockito, Testcontainers |
| Security tests | OWASP ZAP / custom adversarial tests |
| Performance | k6/Gatling |
| Containers | Docker |
| Orchestration | Kubernetes |
| Monitoring | Prometheus/Grafana |

Verify the supported Spring Cloud Gateway deployment variant, starter artifacts, and version compatibility against official documentation.

Do not mix servlet and reactive Gateway APIs incorrectly.

---

# PART X — PROJECT STRUCTURE

## 40. SOURCE CODE ORGANIZATION

Create a maintainable multi-module Maven repository.

```text id="b9xv71"
enterprise-api-gateway/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── control-plane.md
│   ├── data-plane.md
│   ├── routing.md
│   ├── authentication.md
│   ├── authorization.md
│   ├── api-keys.md
│   ├── rate-limiting.md
│   ├── quotas.md
│   ├── resilience.md
│   ├── security.md
│   ├── capacity-planning.md
│   ├── performance.md
│   ├── runbook.md
│   └── decisions/
├── gateway-data-plane/
├── gateway-control-plane/
├── gateway-policy-core/
├── gateway-security/
├── gateway-rate-limiter/
├── gateway-quota/
├── gateway-admin-portal/
├── example-services/
│   ├── order-service/
│   ├── payment-service/
│   └── inventory-service/
├── integration-tests/
├── security-tests/
├── benchmarks/
├── docker/
├── helm/
└── .github/workflows/
```

Adapt modules to actual reuse and deployment boundaries.

Avoid giant shared `common` modules that couple unrelated domains.

Keep the data-plane hot path lean.

---

# PART Y — ADMINISTRATION REST APIS

## 41. REQUIRED APIS

### API Registry

- `POST /api/v1/admin/apis`
- `GET /api/v1/admin/apis`
- `GET /api/v1/admin/apis/{apiId}`
- `PATCH /api/v1/admin/apis/{apiId}`
- `DELETE /api/v1/admin/apis/{apiId}`

### Routes

- `POST /api/v1/admin/routes`
- `GET /api/v1/admin/routes`
- `PATCH /api/v1/admin/routes/{routeId}`
- `DELETE /api/v1/admin/routes/{routeId}`

### API Consumers

- `POST /api/v1/admin/consumers`
- `GET /api/v1/admin/consumers`

### API Keys

- `POST /api/v1/admin/consumers/{consumerId}/keys`
- `POST /api/v1/admin/keys/{keyId}/rotate`
- `DELETE /api/v1/admin/keys/{keyId}`

### Rate Limits

- `POST /api/v1/admin/rate-limits`
- `PATCH /api/v1/admin/rate-limits/{policyId}`
- `GET /api/v1/admin/rate-limits`

### Quotas

- `POST /api/v1/admin/quotas`
- `GET /api/v1/admin/quotas`
- `GET /api/v1/admin/consumers/{consumerId}/usage`

### Configuration

- `POST /api/v1/admin/configurations/publish`
- `GET /api/v1/admin/configurations/versions`
- `POST /api/v1/admin/configurations/{version}/rollback`

### Gateway Status

- `GET /api/v1/admin/gateways`
- `GET /api/v1/admin/gateways/{gatewayId}/status`

For every endpoint provide:

- Authentication
- RBAC
- Input validation
- Optimistic concurrency
- Idempotency where required
- HTTP statuses
- Error contracts
- OpenAPI documentation
- Audit events

Use RFC 9457 Problem Details where appropriate.

---

# PART Z — FUNCTIONAL DEMONSTRATION

## 42. IMPLEMENT SAMPLE MICROSERVICES

Create three functional Spring Boot backend services.

### Order Service

Provides:

- Create order
- Retrieve order
- Simulated processing latency

### Payment Service

Provides:

- Payment authorization simulation
- Controlled timeout
- Controlled failures
- Idempotent processing

### Inventory Service

Provides:

- Inventory lookup
- Reservation simulation
- Conflict responses

Route all services through the Gateway.

Demonstrate:

- JWT authentication
- API key authentication
- Scope-based authorization
- Distributed rate limits
- Tenant quotas
- Dynamic routing
- Canary routing
- Circuit breaker
- Retry safety
- Timeout handling
- Distributed tracing

---

# PART AA — REQUIRED FAILURE SCENARIOS

## 43. ANALYZE THESE 40 SCENARIOS

1. Two Gateway instances process the same consumer's quota concurrently.
2. Redis rate limiter becomes unavailable.
3. Redis primary fails after token consumption.
4. Global rate-limit key becomes hot.
5. Consumer exceeds monthly quota at the billing-period boundary.
6. API key is revoked while cached.
7. JWT expires during request processing.
8. Identity Provider becomes unavailable.
9. JWKS endpoint becomes unavailable.
10. Signing key rotates.
11. Client supplies forged X-User-ID.
12. Client spoofs X-Forwarded-For.
13. Route update produces a conflicting route.
14. Control Plane publishes invalid configuration.
15. Gateway misses configuration updates.
16. Gateway restarts without Control Plane connectivity.
17. One Gateway runs an older route version.
18. Upstream endpoint becomes unhealthy.
19. Kubernetes removes an endpoint during routing.
20. Payment Service times out after processing a request.
21. Gateway retries an unsafe POST.
22. Circuit Breaker opens during traffic spike.
23. Bulkhead is saturated.
24. Gateway outbound connection pool is exhausted.
25. Client disconnects during upstream processing.
26. Request body exceeds configured maximum.
27. WebSocket connection remains open during deployment.
28. HTTP request smuggling attempt occurs.
29. Encoded path manipulation bypasses a route rule.
30. Open proxy/SSRF attempt targets internal metadata services.
31. CORS policy allows an unintended origin.
32. Tenant A attempts to access Tenant B routes.
33. Administrative API is called without proper permission.
34. Gateway logging exposes bearer tokens.
35. Metrics labels become high-cardinality.
36. Collector/telemetry backend becomes unavailable.
37. Gateway pod crashes during in-flight requests.
38. Regional Gateway outage occurs.
39. Canary rollout causes elevated errors.
40. Rolling deployment introduces incompatible configuration schemas.

For each provide:

- Root cause
- Security impact
- Data integrity impact
- User-visible behavior
- Detection signals
- Prevention
- Recovery
- Automated test
- Residual risk

---

# PART AB — TESTING

## 44. UNIT TESTS

Implement tests for:

- Route matching
- Route precedence
- Route validation
- Policy resolution
- JWT claim mapping
- API key verification
- Rate-limit calculations
- Quota accounting
- Request transformations
- Retry classification
- Circuit breaker configuration
- Tenant resolution
- Configuration snapshot validation

---

## 45. INTEGRATION TESTS

Use Testcontainers for:

- PostgreSQL
- Redis
- Identity Provider where appropriate
- Backend services

Verify:

- Gateway routing
- JWT authentication
- Audience/scope enforcement
- API key authentication
- Key revocation
- Distributed rate limiting
- Quota enforcement
- Dynamic config updates
- Circuit breaker behavior
- Retry behavior
- WebSocket routing
- Trace propagation

Use actual HTTP requests through the Gateway rather than only testing filter classes in isolation.

---

## 46. CONCURRENCY TESTS

Verify:

- Multiple Gateway instances consuming one distributed quota
- Concurrent API key revocation
- Dynamic config snapshot replacement
- Route update races
- Rate-limit burst enforcement
- Quota period rollover
- Circuit breaker probes
- Upstream connection saturation

---

## 47. SECURITY TESTS

Test:

- Invalid JWT
- Expired JWT
- Wrong issuer
- Wrong audience
- Unknown signing key
- Scope bypass
- Forged identity headers
- API key enumeration
- Header spoofing
- SSRF
- Open proxy
- Request smuggling
- Path normalization bypass
- Tenant isolation
- Unauthorized administration
- Sensitive log exposure

Use controlled local targets for security testing.

---

# PART AC — PERFORMANCE ENGINEERING

## 48. BENCHMARK WORKLOADS

Create load tests for:

- 1,000 RPS
- 10,000 RPS
- 100,000 RPS
- 500,000 RPS
- 1 million RPS as a scale target

Compare:

1. Gateway with routing only
2. Routing + JWT verification
3. Routing + API key authentication
4. Routing + rate limiting
5. Routing + quota enforcement
6. Routing + full policy stack
7. Gateway during upstream failures
8. Gateway during dynamic configuration updates

Measure:

- Offered/achieved RPS
- Gateway-only p50/p95/p99 latency
- End-to-end latency
- Authentication failures
- Authorization failures
- Rate-limit rejections
- Redis latency
- Quota evaluation latency
- Upstream connection utilization
- Event-loop CPU
- JVM heap/GC
- Network bandwidth
- Configuration propagation lag
- Error rates
- Retry amplification

Never fabricate benchmarks.

Separate performance estimates from actual measurements.

---

# PART AD — DEPLOYMENT

## 49. DOCKER AND KUBERNETES

Provide:

- Multi-stage Dockerfiles
- Docker Compose
- Gateway data plane
- Gateway control plane
- PostgreSQL
- Redis
- Identity Provider
- Example services
- Admin portal
- Kubernetes Deployments
- Services
- Ingress/Gateway API integration where appropriate
- ConfigMaps
- Secret references
- Resource requests/limits
- HPA
- PodDisruptionBudget
- Readiness/liveness probes
- Graceful shutdown
- Connection draining
- Rolling deployment
- Canary deployment
- CI/CD workflow

The local environment must demonstrate complete authentication, authorization, routing, and quota flows without requiring a paid cloud account.

---

# PART AE — MULTI-REGION ARCHITECTURE

## 50. GLOBAL GATEWAY DESIGN

Compare:

1. Single-region multi-AZ
2. Active-passive multi-region
3. Regional active-active data planes
4. Globally coordinated quota enforcement

Evaluate:

- Global routing
- Regional configuration consistency
- Policy propagation
- Token validation
- Rate-limit accuracy
- Quota allocation
- Regional failover
- Cross-region latency
- Data residency
- RPO/RTO

Do not assume regional Redis clusters automatically provide strictly consistent global quotas.

Document whether quotas are strict, region-partitioned, leased, or approximate.

---

# PART AF — IMPLEMENTATION WORKFLOW

## 51. DEVELOPMENT PHASES

Execute incrementally.

### Phase 1 — Requirements and Architecture

Define trust boundaries, scale assumptions, routing architecture, policy model, consistency requirements, and SLOs.

### Phase 2 — Gateway Foundation

Implement Spring Cloud Gateway, static routing, backend services, and end-to-end proxy tests.

### Phase 3 — Security

Implement JWT/OIDC authentication, API key verification, authorization, trusted header handling, and security tests.

### Phase 4 — Distributed Rate Limiting

Implement atomic Redis-backed limiting, policy scopes, burst behavior, and concurrency tests.

### Phase 5 — Quota Management

Implement daily/monthly quotas, usage accounting, tenant plans, and quota consistency rules.

### Phase 6 — Resilience

Implement timeouts, circuit breakers, bounded retries, bulkheads, and overload rejection.

### Phase 7 — Control Plane

Implement API/route registration, policies, upstream configuration, versioning, validation, and administrative security.

### Phase 8 — Dynamic Configuration

Implement distribution, immutable snapshots, stale-update detection, reconciliation, publication, and rollback.

### Phase 9 — Advanced Routing

Implement weighted routing, canary, API versions, WebSocket proxying, and controlled transformations.

### Phase 10 — Observability

Implement metrics, structured logs, distributed tracing, dashboards, and operational alerts.

### Phase 11 — Security Hardening

Implement threat mitigations, trusted proxy configuration, SSRF prevention, API key protection, and adversarial tests.

### Phase 12 — Admin Portal

Implement a functional administration interface for APIs, consumers, routes, quotas, and configurations.

### Phase 13 — Deployment

Implement Docker Compose, Kubernetes/Helm, CI/CD, and configuration management.

### Phase 14 — Verification

Run unit, integration, concurrency, security, failure, and compatibility tests.

### Phase 15 — Performance

Execute benchmarks, identify bottlenecks, and optimize only based on evidence.

### Phase 16 — Final Architecture Review

Review authentication, authorization, distributed quota correctness, dynamic routing safety, latency, reliability, and operational readiness.

For every phase:

- Implement actual source files.
- Keep the project buildable.
- Run applicable checks.
- Fix discovered failures.
- Preserve unrelated work.
- Document major decisions.
- Distinguish verified behavior from assumptions.

If repository and command-execution tools are available, implement directly.

Otherwise, provide complete source files, paths, and reproducible commands without claiming execution.

---

# PART AG — REQUIRED DELIVERABLES

## 52. COMPLETE PROJECT OUTPUTS

### Deliverable 1 — Requirements

- Functional requirements
- Non-functional requirements
- Security invariants
- Rate-limit/quota guarantees
- Acceptance criteria

### Deliverable 2 — HLD

- Architecture diagrams
- Control/data plane
- Request lifecycle
- Routing architecture
- Security model
- Capacity planning
- Multi-region strategy

### Deliverable 3 — LLD

- Domain model
- UML diagrams
- Sequence diagrams
- Policy model
- Route configuration schema
- Rate-limiting algorithm
- Quota accounting
- API contracts

### Deliverable 4 — Gateway Source Code

- Spring Cloud Gateway
- Dynamic routes
- Authentication filters
- Authorization policies
- API key management
- Redis rate limiting
- Tenant quotas
- Load balancing
- Circuit breakers
- Retries
- Timeouts
- Request transformations
- API versioning
- WebSocket routing

### Deliverable 5 — Control Plane

- API registry
- Consumer management
- Route configuration
- Policy management
- Configuration versioning
- Publishing
- Rollback
- Administration APIs
- Audit history

### Deliverable 6 — Functional Admin Portal

- API registration
- Route management
- Consumer/key management
- Rate-limit policies
- Quota policies
- Usage dashboards
- Configuration publishing/rollback

### Deliverable 7 — Automated Tests

- Unit tests
- Integration tests
- Concurrency tests
- Security tests
- Failure-recovery tests
- Gateway compatibility tests

### Deliverable 8 — Infrastructure

- Docker
- Docker Compose
- Kubernetes/Helm
- CI/CD
- Monitoring

### Deliverable 9 — Performance

- Benchmark scripts
- Actual measurements where executed
- Capacity projections
- Bottleneck analysis

### Deliverable 10 — Documentation

- README
- Architecture Decision Records
- Gateway configuration
- Authentication/authorization
- API key lifecycle
- Rate limiting
- Quota consistency
- Routing
- Security threat model
- Deployment guide
- Incident runbooks
- Known limitations

---

# PART AH — MANDATORY ARCHITECTURE REVIEW

## 53. ANSWER THESE 50 QUESTIONS

1. What is an API Gateway?
2. Why do enterprise microservices use an API Gateway?
3. How does an API Gateway differ from a reverse proxy?
4. How does it differ from a load balancer?
5. What is the difference between control plane and data plane?
6. How does Spring Cloud Gateway work internally?
7. What is Reactor Netty's role?
8. Why must event-loop threads avoid blocking operations?
9. How does dynamic route configuration work?
10. How do you distribute configuration across Gateway instances?
11. How do you prevent invalid route activation?
12. How do you handle configuration version skew?
13. How do you implement path-based and host-based routing?
14. How does weighted/canary routing work?
15. How do you securely discover upstream services?
16. How does JWT validation work?
17. Which JWT claims must the Gateway validate?
18. How does JWKS rotation work?
19. What happens when the Identity Provider is unavailable?
20. How do API keys differ from OAuth2 tokens?
21. How do you store API keys securely?
22. How do you implement API key rotation?
23. How do you implement scope-based authorization?
24. Why is Gateway authorization insufficient for all business permissions?
25. How do you prevent forged identity headers?
26. How do you handle trusted proxy headers?
27. How does distributed rate limiting work?
28. What is the difference between Token Bucket and Sliding Window?
29. How do Redis Lua scripts provide atomicity?
30. How do you enforce limits across multiple Gateway pods?
31. How do you prevent hot-key bottlenecks?
32. What is the difference between rate limits and quotas?
33. How do you enforce monthly tenant quotas correctly?
34. How do you handle quota rollover under concurrency?
35. What happens when Redis becomes unavailable?
36. How do you design fail-open versus fail-closed policies?
37. How does Circuit Breaker protect upstream services?
38. When are retries unsafe?
39. How do you implement bounded deadlines?
40. What is the role of Bulkhead?
41. How do you support WebSocket proxying?
42. How do you prevent an API Gateway from becoming an open proxy?
43. How do you prevent SSRF and request smuggling?
44. How do you implement multi-tenant policies?
45. How do you propagate distributed traces?
46. How do you monitor Gateway latency and saturation?
47. How do you scale a Gateway to one million RPS?
48. How do you design multi-region Gateway deployments?
49. How do you benchmark Gateway overhead accurately?
50. What evidence is required before enterprise production adoption?

For each answer explain:

- Architecture
- Security
- Correctness
- Concurrency
- Failure behavior
- Performance
- Operational trade-offs

---

# DEFINITION OF DONE

## 54. ACCEPTANCE CRITERIA

The implementation is complete only when:

- Java 21 project builds successfully.
- Spring Cloud Gateway starts and routes requests.
- Multiple backend services work through the Gateway.
- JWT authentication works.
- Incorrect issuer/audience/expired tokens are rejected.
- API key issuance, verification, rotation, and revocation work.
- Scope/role authorization works.
- Identity headers cannot be spoofed.
- Distributed rate limits work across multiple Gateway instances.
- Burst traffic is controlled.
- Tenant quotas work according to documented consistency semantics.
- Quota rollover is tested.
- Dynamic routing works.
- Route configuration changes propagate safely.
- Invalid configuration is rejected.
- Configuration rollback works.
- Load balancing works.
- Circuit breaker behavior is tested.
- Unsafe retries are prevented.
- Timeouts and bulkheads protect upstream dependencies.
- API version routing works.
- WebSocket routing works where included.
- SSRF, request smuggling, and header spoofing defenses are tested.
- Admin APIs enforce RBAC.
- Functional admin portal works.
- Structured logs, metrics, and distributed traces are available.
- Docker Compose demonstrates the complete flow.
- Kubernetes manifests are provided.
- Automated tests cover important failure cases.
- Load-test scripts are reproducible.
- Performance targets are measured or marked unverified.
- Remaining security and operational risks are documented.

Do not declare production readiness merely because the Gateway forwards HTTP requests successfully.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **real, production-oriented Enterprise API Gateway Platform using Java 21, Spring Boot, Spring Cloud Gateway, Spring Security, OAuth2/OIDC, Redis, and PostgreSQL**.

Prioritize:

1. **Security:** Correct authentication, authorization, trusted identity propagation, and route safety.
2. **Quota correctness:** Distributed rate limits and explicitly defined usage-quota consistency.
3. **Routing reliability:** Deterministic routes, validated configuration, and safe dynamic updates.
4. **High performance:** Non-blocking hot path, bounded connections, and efficient policy evaluation.
5. **Fault tolerance:** Safe resilience behavior, controlled overload, and recovery from Redis/upstream failures.
6. **Control-plane safety:** Versioned policies, auditing, validation, publishing, and rollback.
7. **Scalability:** Stateless Gateway pods and independently scalable policy/control infrastructure.
8. **Maintainability:** Focused modules and established Spring ecosystem capabilities.
9. **Observability:** Diagnosable request routing, authorization failures, quota rejections, and upstream latency.
10. **Reproducibility:** Complete source, functional backend services, administration portal, tests, benchmarks, and deployment infrastructure.

Begin with a concise architecture assessment and select the Spring Cloud Gateway runtime variant, authentication model, rate-limiting strategy, quota consistency model, and dynamic configuration design.

Then implement the platform phase by phase.

Use available development tools to create source code, compile, execute tests, verify distributed Gateway behavior, benchmark, and fix defects.

At completion report:

- Implemented functionality
- Architecture decisions and trade-offs
- Authentication and authorization guarantees
- Rate-limiting and quota consistency semantics
- Route configuration and rollback guarantees
- Project structure and important files
- Exact build and run commands
- Tests actually executed
- Benchmark results actually measured
- Known limitations
- Remaining security and production risks

**Do not stop after HLD or LLD. Deliver the complete end-to-end Enterprise API Gateway with a functional control plane, secure data plane, OAuth2/OIDC integration, API key management, distributed rate limiting, quotas, dynamic routing, resilience, automated testing, observability, and Kubernetes deployment.**
````