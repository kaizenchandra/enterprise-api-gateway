# Authentication and identity

External Keycloak issues RS256 access tokens. The data plane validates the fixed issuer, `gateway` audience, signature,
allowed algorithm, expiry, nbf when present, subject, tenant and Keycloak `typ=Bearer`. Scope and role checks are
separate and combine with tenant equality using AND. Identity is derived only after validation. Administration requires
`gateway-admin` audience plus `gateway.read`/`gateway.write`. No delegated tenant administrator role is provided.

Nimbus caches JWKs and refreshes for new keys. Known unexpired tokens may validate during an IdP outage; an unknown
signing key needs a successful JWKS lookup. Admission has a two-second authentication deadline. JWT revocation/account
changes take effect at expiry plus configured skew, unless route policy freshness expires first. Expiry after admission
does not cancel an operation already dispatched. Backends remain responsible for resource ownership and business
permissions.

API keys contain a UUID identifier and 32 cryptographically random bytes. SHA-256 verifies the uniformly random secret;
plaintext appears only in the authorized issuance response. Redis is read on every key request, bounded by 500ms; there
is no application key cache. PostgreSQL holds verifier/grants and lifecycle events. A transactional reconciliation queue
repairs registry changes after Redis recovery, with row locks serializing revocation/consumer deletion. Revocation
success applies to subsequent Redis reads, excluding in-flight work and acknowledged writes lost during Redis failover.
Redis failure is fail-closed. Invalid credentials are limited to 30 attempts per minute per actual peer IP using Redis;
production ingress must also enforce connection/DoS limits.

Incoming identity, forwarding, bearer, API-key and cookie headers are removed before upstream dispatch. A short-lived
RS256 `X-Gateway-Identity` assertion contains verified subject, tenant, scopes and roles, issuer `enterprise-gateway`,
audience `example-services`, 30-second expiry. Only gateways receive the private key; backends receive the public key,
validate it and enforce category scopes and tenant ownership. This assertion is replayable during its short lifetime;
network isolation, production TLS/mTLS and key rotation remain necessary. The local environment uses loopback HTTP and
development IdP credentials.

WebSocket authorization occurs at handshake; a four-connection per-consumer per-pod limit and 30-second maximum
connection lifetime bound stale access. Applications carrying privileged messages must authorize each message. Echo is
the implemented demonstration. Tokens are kept in the portal input only, not persisted; the portal uses a strict
self-only CSP. Production interactive login should use OIDC authorization code with PKCE.
