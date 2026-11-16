# Distributed admission

Fixed windows are cheap but burst at boundaries. Sliding counters smooth boundaries approximately; logs are accurate but
consume memory proportional to calls. Token buckets allow explicit bursts at constant storage cost. Leaky buckets shape
queues but queues require bounds. GCRA keeps a virtual arrival time and is efficient but less familiar to operators.
This implementation uses a token bucket per authenticated consumer/route.

Lua reads Redis TIME, refills tokens, checks both quotas, then changes both keys only on admission. All keys use tenant
hash tags for Redis Cluster slot compatibility; a hot tenant has one authoritative serialization point. Denied calls do
not consume quota; calls admitted by Lua count even if local bulkhead later rejects. Retry-After for rate exhaustion is
rounded up; quota exhaustion currently returns a conservative 86400 seconds rather than exact monthly reset.

Rate dimensions implemented: authenticated consumer/route. Quotas: tenant/API daily and monthly. An IP limiter protects
invalid-credential attempts; named subscription plan ceilings resolve at publication. Valid-traffic IP buckets,
tenant/global rate buckets and arbitrary combined multi-policy hierarchies remain unimplemented. Local replicas never
replace distributed counters. Fail closed on Redis error or 500ms deadline; API-key
verification also fails closed.

A global hot key serializes requests; shard by independently owned tenants or issue bounded quota leases with explicitly
bounded overshoot. Do not silently convert a strict global quota to per-pod buckets. Redis Cluster key layout is
compatible; cluster client topology configuration is not supplied here.
