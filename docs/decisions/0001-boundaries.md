# ADR 1: Independent planes and fail-closed admission

Accepted baseline: reactive SCG data plane, servlet/JDBC control plane, versioned full polling, atomic Redis token+quota
admission. Explicitly avoid blocking data-path JDBC and local-only global counters. Redis unavailability fails closed. A
5-minute config freshness bound limits stale security exposure but couples availability to prolonged control loss.
Rollback republishes history as a new version. POST retries are forbidden; only explicitly configured safe reads can
make one bounded extra attempt. Future strict
paid quotas need a durable ledger because Redis asynchronous failover can lose acknowledged counters.
