# Administration API and data model

Base `/api/v1/admin`; bearer audience `gateway-admin`, read scope `gateway.read`, write scope `gateway.write`. Operators
are platform-wide; do not grant these scopes to tenant administrators. Full contracts are in [OpenAPI](openapi.json).

| Operation                                                      | Contract                                                                                                               |
|----------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| POST /apis, /routes, /consumers, /rate-limits, /quotas, /plans | Create JSON resource with stable id; 201/409                                                                           |
| GET /{kind}, /{kind}/{id}                                      | Bounded list (10000) or inspection; 200/404                                                                            |
| PATCH /{kind}/{id}                                             | Full replacement, numeric If-Match required; 200/412                                                                   |
| DELETE /{kind}/{id}                                            | If-Match required; 204/412; API deletion disables routes, consumer deletion disables and revokes keys                  |
| POST /consumers/{id}/keys                                      | scopes, roles, days 1..365; one-time secret, 201                                                                       |
| POST /keys/{id}/rotate                                         | Replacement issuance and old-key revocation; caller must handle partial external-store failure                         |
| DELETE /keys/{id}, GET /consumers/{id}/keys                    | Revoke or inspect lifecycle; no secret on inspection                                                                   |
| POST /configurations/publish                                   | Serialized validated immutable full snapshot                                                                           |
| GET /configurations/versions                                   | Last 100 versions                                                                                                      |
| POST /configurations/{version}/rollback                        | Revalidate history, publish a new monotonically increasing version                                                     |
| GET /gateways, /gateways/{id}/status                           | Version/freshness/lastSeen; classify expired heartbeats as offline                                                     |
| GET /consumers/{id}/usage                                      | Tenant/API counters, not a per-consumer billing ledger                                                                 |
| POST /quotas/{tenant}/{api}/adjustments, /reset                | Current UTC day/month correction; usage revision If-Match, reason and idempotencyKey; atomic compare-and-set and audit |
| GET /audit-events                                              | Recent actor/action/target changes without credentials                                                                 |

Route fields include id, apiId, tenant, restricted `/api/vN/name/**` path, optional host, methods, exact approved
upstream origins, scopes, roles, priority (lower first), rate, burst, daily/monthly quotas, timeoutMs, concurrency,
retries (0 or 1, safe methods only), enabled, optional literal X-API-Version/X-Release header predicates. Repeated
approved upstreams express weights. No arbitrary scripts or unrestricted URLs are accepted. Same-priority overlapping
predicates are rejected deterministically. All routes sharing a tenant/API must use identical quotas.

Drafts may reference `rateLimitPolicyId`, `quotaPolicyId`, `planId`; publication resolves them and checks tenant
ownership. Plan ceilings cap rate, burst and both quotas. API plan inheritance and API lifecycle are enforced at
publication. Existing exchanges capture immutable policy; updates do not retroactively alter admitted requests.

PostgreSQL tables are a compact JSONB registry with revision, immutable configuration_versions, api_keys with
verifier/grants/lifecycle, api_key_events reconciliation queue and audit_events. Flyway migrations are immutable and
checksum-guarded by the image build. No blocking database work occurs on gateway event loops or the proxy hot path. CRUD
optimistic locks and publication advisory locks prevent lost updates/partial snapshots. External Redis writes are not a
distributed transaction; reconciliation and fail-closed behavior handle interruption, but async failover can lose state.

Schema version 1 is explicit; future unsupported schemas are rejected and retain the previous valid state. Polling every
three seconds reconciles full snapshots after missed versions. Startup can restore a validated mode-600 local snapshot
without extending its original verification time. Five-minute freshness uses a monotonic live clock; beyond it data
traffic fails closed. Per-pod Kubernetes emptyDir survives container restart but not pod replacement. The first
supported schema requires coordinated migration from the earlier prototype; do not assume arbitrary old binaries
understand new predicates. No cross-version negotiation exists.

Problem Details: validation 400, duplicate 409, missing 404, stale revision 412, authentication 401, authorization 403.
Stable create IDs detect conflicts; publish/rollback and key issuance do not provide durable general Idempotency-Key
replay. Never blindly retry key issuance. Audit/history retention is operator-managed; use external tamper-evident audit
storage and retain referenced rollback versions.
