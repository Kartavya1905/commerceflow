# Security: threat model and controls

This is the threat model behind ARCHITECTURE.md D.4. It is written per trust boundary with STRIDE (spoofing, tampering, repudiation, information disclosure, denial of service, elevation of privilege). Each threat names its control and the test that would fail if the control went missing. A threat without a test says so.

Scope: the system as it runs locally (Docker Compose) and as it would run on a cluster. The payment provider is the self-hosted sandbox (ADR-0008); no real card data exists anywhere in the system (INV-8).

## Assets

| Asset | Why it matters |
|---|---|
| Customer credentials (password hashes) | Account takeover |
| Access and refresh tokens; the JWT signing key | Acting as any user, or as admin |
| Orders and their customers (email, name, what was bought) | Personal data; cross-account disclosure |
| Money: charges and refunds | Charging twice, or keeping money for a failed order |
| Stock | Overselling, or stock held by orders that will never complete |
| Audit trail | Knowing who did what, after the fact |
| Secrets: database, Redis and sandbox passwords and keys | Direct access around every control |

## Trust boundaries

```
 browser ──(1)── web (nginx) ──── api-gateway ──(2)── services ──(3)── Postgres, Redis, Kafka
                                                        │
                                                        └──(4)── payment provider (sandbox)
 operator ──(5)── admin API, dev scripts, .env, secrets/
```

1. **The internet edge**: everything a client sends is hostile until checked.
2. **Gateway to services**: services trust the gateway's routing, never its word on identity. Each service verifies the JWT itself.
3. **Services to their stores**: each service has its own database and least-privilege role (ADR-0006). Kafka messages are trusted only after envelope and schema checks.
4. **To the payment provider**: an outside system that can time out, lie by omission (a lost answer), or answer twice.
5. **Operators**: admins in the app, and whoever holds `.env`.

## Threats and controls

### 1. The internet edge

| Threat | STRIDE | Control | Verified by |
|---|---|---|---|
| Logging in as someone else by guessing passwords | S | Argon2id; lockout after 5 failures for 15 minutes; per-IP rate limit on `/api/v1/auth/**` (5/s, burst 10); the same 401 for an unknown email and a wrong password, with equal hashing work | identity `IdentityApiIT` (lockout); `SecurityIT` (brute force reaches 429) |
| Forged or tampered token | S, E | RS256 with `kid` from the JWKS; `iss`, `aud` and `exp` checked by every service; `alg: none` and HS256 refused | `IdentityApiIT` (forged, expired, wrong audience, unsigned); `SecurityIT` against the stack |
| Stolen access token | S | 15-minute lifetime; memory only in the browser (never web storage, lint-enforced); Bearer header, never a cookie, so no CSRF | Playwright smoke (storage empty); `session.test.ts` |
| Stolen refresh token | S | HttpOnly, Secure, SameSite=Strict cookie on `/api/v1/auth` only; rotated on every use; reuse of a rotated token revokes the whole family | `IdentityApiIT` (reuse revokes the session); `SecurityIT` |
| Acting on another customer's data by guessing ids | I, E | Owner predicate inside every query (INV-7); 404, not 403, so ids cannot be probed | `SecurityIT` (cross-account read and cart); order `OrderApiIT` |
| Customer reaching admin endpoints | E | `ADMIN` required at the gateway and again in each service; the web app only hides admin pages | `SecurityIT`; `GatewayIT`; Playwright smoke (403 from the API) |
| Replaying a checkout request | T | Idempotency-Key per customer: the same key and body give the same order; the same key with a different body gives 422 | `SecurityIT`; `OrderApiIT`; `CheckoutPage.test.tsx` |
| Tampered prices or totals | T | The client never sends a price; order-service prices from catalog; unknown fields are rejected | `OrderApiIT` (client price rejected) |
| Oversized or malformed input | D, T | Bean Validation with size limits; 400 problem bodies; unknown JSON properties rejected | `SecurityIT` (oversized, malformed, injection strings) |
| Request flooding | D | Per-client token buckets at the gateway. Behind nginx, the client address is the right-most `X-Forwarded-For` hop, believed only from the trusted proxy | `ClientAddressTest`; `SecurityIT` (429) |
| Script injection in the web app | T, I | CSP `default-src 'self'` with no `'unsafe-inline'`; React escapes output; `frame-ancestors 'none'`, `nosniff`, `no-referrer` | Header check in `SecurityIT` |
| Error responses leaking internals | I | RFC 9457 problems with fixed text; no stack traces or SQL; a correlation id instead | `ProblemDetailsAdviceTest`; `SecurityIT` (no stack trace in a 500) |

### 2. Gateway to services

| Threat | STRIDE | Control | Verified by |
|---|---|---|---|
| Bypassing the gateway by calling a service port | S, E | Each service validates the JWT and roles itself. Service ports are published on 127.0.0.1 only, for debugging | `SecurityIT` (direct call without a token is 401) |
| Spoofed client address for rate limits | S, D | `X-Forwarded-For` is ignored unless the peer is the trusted proxy | `ClientAddressTest` |
| Internal endpoints reached from outside | E | `/internal/**` is not routed by the gateway | `check-infra` |

### 3. Services to their stores

| Threat | STRIDE | Control | Verified by |
|---|---|---|---|
| SQL injection | T, I | Parameter binding only, never string-built SQL from input | `SecurityIT` (injection strings stay data); code review |
| One service reading another's data | I | A database and role per service; `CONNECT` revoked from `PUBLIC` | `check-infra` (role × database matrix) |
| Forged or malformed Kafka messages | T | Envelope check, schema-validated events on write, poison messages straight to the DLT, dedupe by event id, business keys | `OutboxAndInboxIT`; saga and payment tests |
| A replayed message charging twice or moving stock twice | T | Inbox dedupe in the same transaction; idempotency key = payment attempt id; one attempt per order | `DuplicateDeliveryIT`-style scenarios in `FailureRecoveryIT` |
| Personal data or secrets in logs | I | Code never logs them; `RedactingLogCustomizer` masks emails, JWTs, Bearer values, secret fields, payment tokens and card-like numbers in every JSON log line | `LogRedactionTest`; `SecurityIT` scans the stack's logs |
| Unbounded metric labels (an id as a label) exhausting Prometheus | D | Labels only from bounded sets; unknown event types counted as `other` | `InboundEventsTest`; `SecurityIT` checks every service's metrics |

### 4. The payment provider

| Threat | STRIDE | Control | Verified by |
|---|---|---|---|
| A retried charge charging twice | T | Idempotency key = attempt id, created once | `PaymentIT`, `LateChargeIT`, mutation M2 |
| A lost answer leaving money taken for a failed order | T | Timeout means UNKNOWN; the reconciler settles from the provider's records; a late charge on a closed attempt is refunded | `ReconcilerIT`; failure scenarios |
| A slow provider exhausting connections | D | No database transaction open during a provider call (enforced); timeouts; circuit breaker | `PaymentIT` |
| Leaked provider key | S | Only in `.env` (git-ignored) and the container environment; compared in constant time; never on a command line or in a log | gitleaks; `LogRedaction` |

### 5. Operators

| Threat | STRIDE | Control | Verified by |
|---|---|---|---|
| An admin action nobody can trace | R | Audit rows (actor, action, resource, outcome, correlation id) for product, stock, dead-letter replay and reconcile-now actions | `DeadLetterReplayIT`, `ReconcileNowIT` |
| Secrets committed to git | I | `.env`, `secrets/`, keys and Playwright traces git-ignored; gitleaks over the whole history in CI | `security` workflow |
| A vulnerable dependency or base image | E | Trivy on dependencies, misconfiguration and every built image, failing on HIGH or CRITICAL with a fix; CodeQL on Java and TypeScript; weekly scheduled scan | `security` workflow; `ci` image scan |
| Fault injection left on | D | Off by default, wired only by `chaos.yml`, logged at startup, alerted (`FaultInjectionOn`) | `alerts.test.yml` |

## Accepted risks

These are known and accepted for a local, single-host deployment. Each says what would change it.

- **No TLS inside the stack, and plaintext, unauthenticated Kafka.** Everything binds to 127.0.0.1 or the private Compose network. A cluster deployment (Milestone 10) needs TLS at the edge and SASL or mTLS for Kafka.
- **Access tokens cannot be revoked before they expire (15 minutes).** Logout revokes the refresh family, so a stolen access token works until its expiry. A shorter lifetime, or a revocation list checked by the gateway, would narrow this.
- **One Postgres server for every database.** Isolation is by role and database, not by host (ADR-0006).
- **The sandbox's own logs are not redacted.** It stands in for an outside provider, and adding the security library would also add Spring Security to it. Its logs hold test tokens only.
- **Rate limits fail open when Redis is down.** Availability is preferred; login keeps its per-account lockout.

## Reporting a vulnerability

This is a portfolio project, not a service with users. Please open a GitHub issue without exploit details, and the maintainer will follow up privately.
