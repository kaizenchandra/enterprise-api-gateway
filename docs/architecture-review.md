# Architecture review

The answers describe this implementation and its trade-offs; planned production behavior is explicitly distinguished.
Cross-cutting concurrency, security, failure and operational detail is in the linked design documents.

1. **What is an API Gateway?** An ingress policy and routing boundary. This implementation authenticates, authorizes and
   admits before SCG forwards; it cannot replace backend business rules.

2. **Why use one?** Centralized common policy reduces duplication and provides bounded traffic protection; it adds an
   availability dependency and latency that require measurement.

3. **Reverse proxy difference?** Reverse proxies forward traffic; this platform adds consumer identity, versioned policy
   and coordinated usage accounting. Product boundaries often overlap.

4. **Load balancer difference?** A load balancer chooses endpoints; a gateway also governs API access and contracts.
   Kubernetes Services perform health-aware balancing here.

5. **Control/data plane?** Control owns durable administration and publication. Data owns live reactive traffic using
   immutable local routes and distributed admission.

6. **SCG internals?** WebFlux handler mappings select Routes and compose ordered GatewayFilters and GlobalFilters. Netty
   routing streams the request/response; route cache refresh is asynchronous.

7. **Reactor Netty role?** It implements event-driven HTTP transport, pooling and backpressure; parser and timeout
   defaults remain part of the security boundary.

8. **Why avoid blocking?** A few event loops serve many sockets. Blocking JDBC or waiting semaphore acquisition stalls
   unrelated tenants; JDBC lives only in the servlet control plane.

9. **Dynamic routes?** Compile a validated full snapshot into immutable SCG Routes, atomically replace locator state and
   refresh caches; existing exchanges finish under captured policy.

10. **Distribution?** Poll latest full snapshot every three seconds with separate control authentication. Missing
    versions reconcile without replaying all events; control load grows with pod count.

11. **Invalid activation?** Validate full route set and allowed targets before publish and again before compilation.
    Failure retains the prior version; freshness still eventually expires.

12. **Version skew?** Monotonic versions reject regression, rollback creates a new version, and five-minute freshness
    caps stale exposure. Heartbeat status reports per-pod versions. Schema 1 rejects unsupported envelopes; arbitrary
    mixed-version schema negotiation remains unsupported.

13. **Path/host matching?** SCG Path/Host/Method predicates plus deterministic priority. Reject ambiguous same-priority
    overlaps; restricted path vocabulary favors auditable safety.

14. **Canary routing?** Randomly select among allowlisted endpoints; repetition gives coarse weights. This is not sticky
    assignment. Explicit literal X-Release/X-API-Version predicates provide header canaries; automated health-aware
    rollout rollback remains external.

15. **Secure discovery?** Operator-owned Kubernetes Service origins, readiness-driven endpoints and egress isolation.
    DNS alone is not service identity; use mTLS.

16. **JWT validation?** Nimbus verifies RS256 with trusted JWKS and validators for issuer/time/audience/type/claims.
    Signature validity does not imply current account activity.

17. **Required claims?** Issuer, audience, subject, expiry, tenant and access-token type, plus nbf when supplied; scope
    and roles are then authorization inputs.

18. **JWKS rotation?** Nimbus refreshes public keys for trusted issuer; unknown kid fails if refresh unavailable.
    Rotation and outage cache behavior must be soak-tested.

19. **IdP unavailable?** Known cached keys may validate unexpired tokens. New unknown keys fail; current account
    revocation is bounded by token lifetime and skew.

20. **Keys versus OAuth tokens?** Keys identify a noninteractive application with static grants; JWTs carry
    issuer-signed short-lived claims. Neither establishes resource ownership.

21. **Store API keys?** Store identifier and SHA-256 verifier of a 256-bit random secret; plaintext shown only during
    issuance, never in logs or query strings.

22. **Rotate keys?** Issue a replacement then remove the old Redis verifier. PostgreSQL events and locked reconciliation
    repair Redis state; the stores are not a distributed transaction and async Redis rollback remains a risk.

23. **Scope authorization?** Require all route scopes and roles plus exact tenant equality after authentication. Missing
    permission denies before Lua and backend dispatch.

24. **Why backend authorization?** Only business services know resource ownership and business state. They must
    authenticate gateway/token and enforce row/resource boundaries.

25. **Forged identity?** Remove client identity assertions and inject authenticated context. Both JWT and API-key
    callers receive a short-lived RSA-signed context verified by the backend. External credentials are stripped.
    Production TLS/mTLS and business checks remain necessary.

26. **Trusted proxy headers?** The implementation discards forwarded metadata entirely. Production proxy-derived IP
    requires exact
    trusted-hop configuration; never trust the first supplied XFF element.

27. **Distributed limiting?** Atomic Lua refills/checks consumer buckets and tenant/API quotas in one hash slot. No
    per-instance counter is used for global accounting.

28. **Bucket versus sliding window?** Buckets allow capacity-sized bursts and constant storage; sliding windows bound
    recent totals with more state or approximation. Neither replaces concurrent-call admission.

29. **Lua atomicity?** One Redis primary executes script without interleaved operations; co-located keys prevent cluster
    cross-slot errors. Atomic execution does not imply durable failover consensus.

30. **Across pods?** All pods use the same tenant/API keys and Redis server clock. Local concurrency bulkheads are
    intentionally separate from distributed accounting.

31. **Hot keys?** Partition independently owned budgets, or explicit bounded leases. Sharding one hard global budget
    into independent counters breaks the promised limit.

32. **Rate versus quota?** Rate bounds frequency/burst; quota bounds period usage. This platform admits only if both
    constraints allow it.

33. **Monthly quotas?** Redis server UTC Gregorian month selects counters; atomically reset stale month and increment.
    Contractual no-loss quotas require a durable ledger.

34. **Concurrent rollover?** Period check/reset/increment occurs inside one Lua execution, so racing calls cannot each
    reset the same active period. Boundary tests remain necessary.

35. **Redis unavailable?** Bounded dependency timeout returns 503 and no dispatch. Do not silently fail open for paid
    usage or abuse protection.

36. **Fail open/closed?** Security and strict admission fail closed; telemetry may drop to preserve service. Static
    last-valid routes have a finite freshness budget.

37. **Circuit breaker?** Per-route breakers stop repeated transport-failing calls and probe recovery. HTTP
    500/502/503/504 also count; slow-call policy tuning remains workload dependent.

38. **Unsafe retries?** Non-idempotent POSTs may already have committed after timeout. POST retry configurations are
    rejected; GET/HEAD may opt into one jittered retry within the overall deadline. Fleet-wide adaptive retry budgets
    remain external.

39. **Deadlines?** Connect/acquire/response and overall reactive deadlines bound HTTP work. Cancellation releases local
    slots but cannot undo upstream business effects.

40. **Bulkhead?** Immediate local atomic admission bounds route/process concurrency without blocking. Saturation returns
    rejection; it is not a distributed quota.

41. **WebSockets?** SCG can proxy ws/wss, but handshake auth is only entry authorization. Native-channel admission
    persists until close, with four connections per consumer per pod and a 30-second lifetime. Message authorization
    remains application-owned.

42. **Prevent open proxy?** Only persisted, validated operator-approved origins can be selected. Client paths/headers
    never choose arbitrary destinations; pair with network egress restrictions.

43. **SSRF/smuggling?** Exact origins, strict route/path/framing validation and maintained Netty parser reduce attack
    surface. DNS and differential parser fuzzing remain residual risks.

44. **Multi-tenant policies?** Verified tenant must match route tenant and selects quota hash slot. Named plan/policy
    resolution occurs at publication; delegated tenant administration remains outside the platform operator model.

45. **Trace propagation?** Micrometer OTel bridge supplies W3C transport context with sampling. Collector tests verify
    sampled trace continuity, bounded exporter failure and redaction; durable trace storage remains an operator
    deployment task.

46. **Monitor latency?** Combine SCG request metrics, local policy rejection counts, config age/version, JVM and Netty
    pools, Redis latency and breaker states. Avoid unbounded labels.

47. **Million RPS?** Measure per-pod capacity, identify Redis hot spots/network/TLS/JWT costs, shard safely and
    distribute generators. No measured evidence here supports that target.

48. **Multi-region?** Start multi-AZ; active-active needs owned regional quotas, explicit RPO/RTO, residency and config
    convergence. Independent regional Redis is not a strict global ledger.

49. **Benchmark overhead?** Matched direct/proxied workload with controlled payload/protocol/TLS/auth cache state;
    report admitted versus rejected requests and offered versus achieved load. Do not subtract unrelated p99s.

50. **Production evidence?** Passing HTTP/security/concurrency/failure suites, independently reviewed trust boundaries,
    fuzzing, dependency/SBOM scans, target-scale benchmarks, DR drills and operational ownership. Local runtime
    acceptance passed; production adoption gates in verification.md remain open.
