# Enterprise API Gateway

Java 21, Spring Boot 4.0.9, Spring Cloud 2025.1.4 / Gateway Server WebFlux 5.0.4. A runnable control/data plane platform
with reproducible HTTP, concurrency, fault and security tests. [Verification](docs/verification.md) records actual
evidence and remaining production gates; target throughput and availability are not certifications.

Four Maven modules keep the reactive proxy hot path separate from PostgreSQL administration: `gateway-policy-core`
(immutable contracts/validation), `gateway-control-plane` (registry, keys, policy resolution, audit, portal),
`gateway-data-plane` (SCG/Netty) and `example-service` (three isolated orders/payments/inventory instances).
`gateway-admin-portal` contains the DOM/live-HTTP portal test. Docker, Kubernetes manifests, load scripts and operating
documents are included.

## Build and run

```sh
# Select Java 21; set the appropriate path on other operating systems.
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
mvn -B clean verify
bash scripts/build-images.sh
# Uses preserved/random local secrets and source fingerprints; no paid cloud needed.
docker compose --env-file docker/.env -f docker/compose.yml up --no-build -d --wait --wait-timeout 600
python3 scripts/demo.py
python3 scripts/distributed_http_test.py
python3 scripts/acceptance.py --faults
npm ci --prefix gateway-admin-portal
npm test --prefix gateway-admin-portal
```

Local endpoints: gateway `http://localhost:48080` and `48082`, portal/control `48081`, Keycloak `48180`, gateway metrics
`http://localhost:49080/actuator/prometheus`. The demo obtains tokens and seeds APIs/routes. Portal administration
token: client credentials `admin` / `local-admin-secret` at
`http://localhost:48180/realms/gateway/protocol/openid-connect/token`. The console supports
API/route/consumer/policy/plan forms, revision-aware editing, keys, usage, audit, publication and rollback. Tokens are
not persisted. Compose uses loopback HTTP and development IdP credentials; do not expose it publicly.

Tests need a reachable Docker daemon; check reports for skipped container tests. `acceptance.py --faults` deliberately
stops/restarts only this project's Redis/control/IdP/gateway services and restores them in finally blocks. Avoid running
benchmarks concurrently with correctness tests.

```sh
# Optional monitoring and live telemetry acceptance
TRACING_ENABLED=true docker compose --env-file docker/.env -f docker/compose.yml --profile monitoring up --no-build -d --wait --wait-timeout 600
python3 scripts/observability_test.py
python3 scripts/dashboard_test.py
python3 scripts/verify_images.py
# Prometheus http://localhost:49090 ; Grafana http://localhost:48030
# k6 must be installed. Measured modest local loads, not a target-scale certification.
python3 scripts/benchmark.py
# Public Maven dependency advisory gate
mvn -B org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DoutputFormat=json
python3 scripts/scan_dependencies.py
bash scripts/scan_images.sh
# Refresh OS patch installation on future security rebuilds:
# bash scripts/build-images.sh --no-cache --pull
# Stop services while preserving state
docker compose --env-file docker/.env -f docker/compose.yml --profile monitoring down
```

## Guarantees and boundaries

* RS256 OAuth2 JWT validation uses trusted issuer/JWKS, audience, time, type and required claims. API keys have random
  256-bit secrets, stored verifiers, no data-plane cache, lifecycle reconciliation and revocation. Tenant/scope/role
  rules combine with AND before upstream dispatch.
* Untrusted identity, forwarded headers and external credentials are stripped. Backends validate short-lived
  gateway-signed identity assertions with public keys, enforce category scopes and retain tenant/resource ownership
  checks. Production transport protection and key rotation remain operator responsibilities.
* Atomic Redis Lua checks consumer/route token buckets plus tenant/API daily/monthly UTC quotas across pods. All
  Lua-admitted calls count, including upstream failures and subsequent local overload rejection. Corrections/reset use
  usage revisions and idempotency. Named policies and tenant plan ceilings resolve at publication.
* Counter correctness assumes one authoritative Redis primary retains acknowledged writes. Async failover, storage
  restore or independent regional clusters can lose usage/revocations. Contractual no-loss global quotas require a
  transactional/consensus ledger or partitioned entitlements. This implementation does not promise those guarantees.
* Complete immutable snapshots validate at save/publication/activation. Exact operator upstream allowlists prevent
  arbitrary proxy destinations. Three-second full polling heals missed versions; atomic replacement, persisted
  last-valid cache, schema validation and five-minute freshness bound protect recovery. Rollback creates a new
  increasing version; it does not mutate history.
* POST retry configurations are rejected. GET/HEAD may opt into one jittered retry under the same deadline. Per-route
  circuit breakers, bounded connections/queues and immediate local concurrency admission protect dependencies. Signed
  WebSocket handshake, per-consumer per-pod connection caps and 30-second lifetimes are demonstrated; responses stream,
  while chunked/large uploads are deliberately restricted.
* Platform operators use a separate admin audience and scopes with revision preconditions and audit events. There is no
  delegated tenant-admin role or general durable idempotency replay for key issuance/publication. Demo service state is
  memory-only.

See [architecture](docs/architecture.md), [compatibility](docs/compatibility.md), [administration/OpenAPI](docs/control-plane.md), [authentication](docs/authentication.md), [quota semantics](docs/quotas.md), [resilience](docs/resilience.md), [security](docs/security.md), [performance](docs/performance.md), [failure review](docs/failure-scenarios.md), [capacity](docs/capacity-planning.md)
and [runbook](docs/runbook.md). Kubernetes manifests require operator image/secret/IdP/storage/TLS
configuration; [deployment notes](helm/README.md) describe the boundary.
