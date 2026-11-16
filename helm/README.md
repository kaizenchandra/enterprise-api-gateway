# Kubernetes deployment templates

Plain Kubernetes manifests are supplied; this directory is not a packaged Helm chart or a Kustomize overlay.
`platform.yaml` contains data/control deployments, services, HPA, PDBs, probes, resources, zone spreading, graceful
termination, non-root/read-only filesystem settings and per-pod snapshot storage. `examples.yaml` supplies three backend
workloads with signed identity verification. `network-policy.yaml` restricts ingress/egress, and `ingress.yaml` is a
TLS/hostname template requiring operator configuration.

Before deployment, set image registry/digests, namespace/DNS (the example infrastructure addresses use namespace
`gateway`), PostgreSQL/Redis endpoints, OIDC issuer/JWKS, TLS ingress secret/hosts, approved upstream origins, telemetry
and secret-manager references. Gateways alone receive the signing private key; backends receive only its public key.
Managed database/Redis, their TLS/ACL/backup policies and an IdP must be provisioned separately. Check
ingress-controller and monitoring namespace labels against network policies. Do not apply example.invalid hosts or
placeholder image names as a production deployment.

```sh
# Schema/admission validation only, against an existing test namespace:
kubectl -n YOUR_TEST_NAMESPACE apply --dry-run=server -f helm/
# After the infrastructure, images, secrets, namespace and policies have been reviewed:
kubectl -n gateway apply -f helm/
kubectl -n gateway rollout status deployment/control
kubectl -n gateway rollout status deployment/gateway
```

Readiness includes configuration freshness, but a rollout also needs a protected HTTP request and per-pod version check.
emptyDir retains a cache across container restart but not pod replacement. WebSockets cannot migrate between pods; their
30-second lifetime is below the 45-second termination grace, and clients must reconnect with backoff. Active endpoint
draining, autoscaling, multi-AZ failover and production TLS/mTLS require deployment tests.

Local verification accepted 20 resources using a server-side dry-run in OrbStack. Application workloads were not
installed there. This proves admission/schema validity, not DNS, secret correctness, routing or production availability.
The complete runnable end-to-end environment is Docker Compose, documented in the root README.
