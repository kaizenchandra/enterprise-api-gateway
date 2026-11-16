# Operations

Startup: deploy dependencies; validate issuer and exact upstream allowlist; start control plane/Flyway; start gateways;
seed/publish; wait for route convergence; exercise a protected request on every pod before ingress. Readiness includes
configuration freshness; still verify a protected HTTP request. Monitor `gateway.configuration.age` and version, policy
rejections,
SCG request metrics, JVM/Netty and Redis. Do not expose management ports on public ingress.

Redis outage: admission fails 503; stop external load, restore authoritative storage, reconcile usage/revocations before
reopening. Never temporarily switch paid quotas to per-pod counters. Control outage: existing snapshots last up to five
minutes, then requests fail 503. Restore PG/CP and verify all pod versions. A cold container may restore its local
validated snapshot within the original freshness budget; an empty or expired cache requires control connectivity. A
missed notification is harmless because polling fetches full latest content.

Bad rollout: validate historical snapshot remains compatible with current allowlist; invoke rollback (new version);
inspect every pod, then restore traffic. Emergency route removal takes effect eventually and cannot recall
already-dispatched requests; use ingress/mesh revocation for urgent cutoffs. JWT compromise: rotate issuer keys and
shorten token lifetime; established cached keys/tokens need explicit revocation strategy.

Upstream outage: check route breaker, transport errors, pool utilization and backend readiness. Payments must use stable
idempotency keys and reconcile ambiguous outcomes. Never blindly retry failed POSTs. Shutdown: remove readiness and
drain ingress; Spring graceful shutdown waits for in-flight HTTP until configured platform grace period. WebSockets
close within their 30-second lifetime; clients must reconnect with backoff. Cross-pod connection migration is
unavailable.

Logs: structured ECS; do not enable request/authorization wire logging. Telemetry must use bounded exporters, sampling
and drop on collector failure. Trace IDs must not become metric dimensions. Rate rejections have only bounded status
labels. Collector redaction and outage tests are reproducible in scripts/observability_test.py; operator retention and
durable storage remain adoption gates.

Production secrets: external Secret manager; no generated credentials in git; separate DB migration identity from
runtime; network policies restrict data plane to approved backend/Redis/control/issuer/OTel destinations. TLS ingress,
mTLS internally, non-root/read-only containers, image scanning, Redis ACL/TLS, PostgreSQL TLS/backups and full disaster
drills remain required.
