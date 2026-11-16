# Quota correctness

Rate limits control frequency and bursts. Quotas count total admitted usage within a UTC day and Gregorian calendar
month. The Lua script derives both from Redis TIME; gateways' clocks do not select periods. No reservation/refund: all
admitted requests are counted, regardless of upstream error or client cancellation. An admitted GET/HEAD may make at
most one configured retry without consuming quota twice. A
transport failure after Lua commits can consume quota without dispatch. This favors bounded spend protection over exact
successful-operation billing.

Quota counters are tenant/API, shared by all consumers and gateway instances, atomically checked and incremented with
token consumption. Publication must reject divergent quotas for routes sharing a tenant/API. Old/new policy versions can
coexist during convergence: lowering quota is eventually enforced, and prior policies may allow traffic for up to the
freshness bound. Daily rollover resets only d, monthly rollover resets only m. TTL 62 days avoids stale storage without
deleting a live monthly counter.

Strict accounting requires acknowledged writes never be lost. Standalone Redis and one primary at a time provide atomic
execution; asynchronous replicas and failover do not guarantee linearizable durable counters. Redis eviction is
disabled. Missing counters after restore reset usage, so restore must reconcile an authoritative ledger before traffic
resumes. No analytics ledger is implemented. For paid contractual hard limits, implement transactional PostgreSQL
reservations or consensus-backed storage, accepting higher latency. Multi-region recommendation: region-partitioned
entitlements whose sum never exceeds the global contract, with controlled transfer; not independent full quotas per
region.

Administrative correction/reset operates on current UTC periods under the same tenant hash tag. If-Match usage revision
prevents overwriting concurrent admission; idempotency keys replay the original correction result. A correction is
audited and requires platform write scope. Monthly/day rollover and clock rollback clamps are covered by real Redis
tests. These counters are operational usage, not an invoice ledger.
