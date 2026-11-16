# Final verification evidence

Verified locally on 2026-11-15 using Java 21 and the source-built Docker images. The remaining HTTP failures were fixed
and the final images were rerun through functional, distributed, security, resilience and dependency-outage checks. This
establishes the implemented local runtime behavior; production deployment and scale targets remain subject to the gates
below.

Source fingerprint: `04e40a21ba2ad54ba5b66a1d52e9a854f99a1c2f2201fc31914662929392d9b8`. All six running application
images carry this revision. `results/evidence.json` validates image IDs against scans and jar inspection, records 12
running services, and hashes the preserved successful logs.

## Executed checks

| Check                                | Actual result / evidence                                                                                                                                                                                                                      |
|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Java 21 reactor verification         | 49 tests across 13 suites; 0 failures, errors or skips. Real PostgreSQL and Redis Testcontainers ran. `results/java-tests.json`, `results/final-tests.log`                                                                                    |
| Source Docker build and health       | Multi-stage build completed; all six application images rebuilt and all 12 Compose services running. `results/final-images.log`, `images.json`                                                                                                |
| Runtime image inspection             | Every application jar: Java class major 65, Java 21.0.13 runtime, UID 10001, no test libraries or generated secrets in application classes/resources; replica jar hashes identical. `image-verification.json`                                 |
| Complete functional HTTP demo        | Both gateways, Keycloak JWT, scopes, key issuance/rotation/revocation, admin isolation, forged-header stripping, order create/get, inventory lookup/reservation, payment idempotency and conflict. `final-demo.log`                           |
| Distributed HTTP concurrency         | Exactly 5 admitted/HTTP 200 and 35 quota 429 from 40 calls through two gateways; zero dependency failures. Revision race 200/412, invalid publication 400, monotonic rollback. `final-distributed.log`                                        |
| Advanced HTTP/security/failure suite | All 10 groups below passed on the final images. `acceptance.json`, `final-acceptance.log`                                                                                                                                                     |
| Portal                               | DOM + real HTTP: CSP/assets, forms, create/select/revision update/delete, usage table, fleet and versions. `final-portal.log`. Native visual browser QA was unavailable because the automation host stalled; no visual-browser claim is made. |
| Telemetry                            | Same sampled W3C trace at gateway/backend; authentication/admission spans; three Prometheus targets UP; 20/20 HTTP 200 during collector outage; credential/query canaries absent from logs/metrics after redaction. `observability.json`      |
| Dashboard                            | Grafana database healthy, provisioned gateway dashboard and Prometheus datasource, live request metric series. `dashboard.json`                                                                                                               |
| Maven supply chain                   | 195 public Maven components queried with OSV: zero matching packages after Boot/Cloud and complete OTel BOM updates. `bom.json`, `dependency-scan.json`                                                                                       |
| Runtime OS supply chain              | Trivy 0.76.2 scans of three distinct runtime application images: zero critical/high; 31 medium and 4 low per image, all without published fixes in that database. No findings hidden. `image-scan-summary.json`, `trivy-*.json`               |
| Portal test dependencies             | npm audit: zero advisories in the locked jsdom test toolchain. This is separate from Maven/container scans.                                                                                                                                   |
| Kubernetes manifests                 | Server-side dry-run accepted all 20 resources in the local verification namespace. No application workload or multi-AZ deployment was performed.                                                                                              |
| Benchmarks                           | Actual 15-second local runs with strict zero HTTP failure threshold; dropped load reported below. `benchmark-summary.json`, `k6-*.json`, `benchmark-*.prom`, resource snapshots and Redis commandstats                                        |

## Advanced acceptance groups

| Group                                      | Result         |
|--------------------------------------------|----------------|
| final image provenance                     | PASS (0.454s)  |
| adversarial HTTP boundaries                | PASS (0.395s)  |
| role and tenant isolation                  | PASS (3.616s)  |
| named policies, plans and quota correction | PASS (8.409s)  |
| routing, versioning and deactivation       | PASS (4.427s)  |
| burst, safe retry, deadline and bulkhead   | PASS (9.092s)  |
| circuit opening and recovery               | PASS (20.694s) |
| WebSocket and streaming                    | PASS (10.292s) |
| dependency and configuration recovery      | PASS (55.175s) |
| distributed invalid-credential throttling  | PASS (6.764s)  |

The resilience checks observed one actual dispatch of a timed-out payment, bounded safe read retry with two attempts,
seven local bulkhead rejections and one deadline response from eight concurrent writes, 20 breaker failure dispatches,
no backend dispatch while open, and ten successful recovery probes. WebSocket echo, a fifth-connection 429 and
incremental SSE bytes passed. Fault tests restarted a cached gateway without control connectivity, denied stale policy
using an explicit eight-second test override (production default 300s), reconciled missed versions, failed closed during
Redis loss, recovered keys and accepted a known cached JWT during issuer loss.

## Measured limits

| Offered RPS | Completed requests | Achieved RPS | HTTP failures | Dropped iterations | End-to-end p99 ms | Policy-admission p99 ms |
|------------:|-------------------:|-------------:|--------------:|-------------------:|------------------:|------------------------:|
|         100 |               1468 |        97.80 |            0% |                 33 |            721.12 |                  377.60 |
|        1000 |              10255 |       672.56 |            0% |               4746 |            390.87 |                  324.94 |

These are HTTP/1.1, loopback Docker, no TLS, warmed signing-key cache, JWT + RSA internal identity + Redis admission,
monitoring enabled, one generator and maximum 128 VUs. Policy-admission time is **not full gateway overhead**. Dropped
iterations mean neither offered load was fully sustained in this run. No 100k/million-RPS, p99<10ms, 100k connections or
99.99% availability claim is supported. See [performance](performance.md) for raw boundaries and the measured bulkhead
defect/fix.

## Acceptance and production boundaries

The local definition-of-done items for build/start, all three services, JWT/key/authz, spoofing defenses, distributed
quota/burst, dynamic route/publish/rollback, safe retries/breaker/deadline/local concurrency, version/header/weighted
routing, WebSocket/streaming, admin RBAC/portal, telemetry, Compose, Kubernetes manifests and reproducible tests/load
scripts are evidenced above. Expired/wrong-issuer/unknown-key tokens are covered by real RSA/JWKS decoder tests; live
Keycloak HTTP checks cover correct routing, wrong audience/signature and scope. Do not describe the decoder cases as
independent full HTTP tests.

Remaining production gates are explicit:

* Redis guarantees atomic single-primary admission while acknowledged writes survive. Replica failover/storage restore
  can lose counters or revoked state. No strict durable global paid-quota ledger or multi-region enforcement is
  implemented. Registry event reconciliation repairs key state but is not distributed consensus.
* TLS ingress, workload/server mTLS, Redis ACL/TLS, database TLS/backups, external secret/key rotation, tamper-evident
  audit, ingress slow-client/connection controls and disaster recovery require production integration and drills.
  Infrastructure/IdP/monitoring images were not included in the application OS scans.
* The runtime OS reports 31 medium/4 low unfixed advisories per image. Recheck and rebuild when vendor fixes appear;
  `bash scripts/build-images.sh --no-cache --pull` refreshes OS package installation. No vulnerability-free or
  production-ready claim is made.
* Target-scale throughput, tail latency, 10k-route validation complexity, per-event-loop CPU profiles, pool saturation,
  Redis failover, JWKS rotation storm, parser fuzzing through the actual ingress, node/region outage and rolling
  WebSocket reconnect drills remain unverified.
* Platform operators are global; no delegated tenant administration, approval workflow, external PDP, arbitrary
  multi-dimensional rate-policy hierarchy or invoice ledger. Named tenant plans cap implemented consumer/route rates and
  tenant/API quotas. Usage views are tenant/API operational counters.
* Configuration converges eventually; heartbeat versions are not a fleet atomic cutover. Schema 1 validates legacy
  fields and rejects unsupported envelopes, but does not negotiate with arbitrary old binaries. A Kubernetes emptyDir
  cache does not survive pod rescheduling. Stale permissions may remain usable for the configured freshness window.
* WebSocket limits are per pod and authorization is at handshake with a 30-second connection lifetime; privileged
  messages need application authorization. Finite SSE is demonstrated; indefinite streams and chunked/large writes are
  not supported by the selected contract.
* Demo business state/idempotency is in memory. Gateway scope checks do not replace production resource authorization,
  durable payment idempotency or tenant data controls. API-key issuance and configuration publication have no general
  durable idempotency replay.
* Portal DOM/live HTTP passed; native browser visual/accessibility/cross-browser testing and a production OIDC PKCE
  login remain outstanding. CI workflow is supplied and its core steps were executed locally; GitHub Actions itself was
  not run.

All runnable local acceptance checks are preserved, along with failed pre-fix benchmark evidence. The full enterprise
production adoption gates remain open; the platform is not certified production-ready.
