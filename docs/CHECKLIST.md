# Implementation checklist

Tick an item only when its tests pass on a clean clone. Each milestone ends with the exit gate in [ROADMAP.md](ROADMAP.md).

## Milestone 1 — Architecture, contracts, repository

- [x] Architecture document, sections A–L ([architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md))
- [x] Business invariants INV-1 … INV-8 defined
- [x] ADR-0001 … ADR-0008 written
- [x] Maven parent (Spring Boot 4.1.2, Spring Cloud 2025.1.4) + Maven Wrapper 3.9.16
- [x] Event envelope + 19 event schemas (JSON Schema 2020-12)
- [x] Topic catalogue `contracts/src/main/resources/events/topics.json`
- [x] OpenAPI 3.1 specs: identity, catalog, order, inventory, payment
- [x] 20 valid + 16 invalid example messages with expected-violation map
- [x] `EventContracts` validator (schema + semantic rules) reusable by producers
- [x] Contract tests: 158 passing; three planted defects each caught
- [x] Spotless formatting gate (LF line endings)
- [x] All 10 Mermaid diagrams render (checked with mermaid-cli)
- [x] CI workflow `.github/workflows/ci.yml`
- [x] Pushed to https://github.com/Kartavya1905/commerceflow; CI green (run 41379890078)
- [x] Tag `m1-done`

## Milestone 2 — Local infrastructure with Docker Compose

- [x] Compose file: core (no profile) + `obs` profile, pinned image tags, all ports on 127.0.0.1
- [x] Postgres init: seven databases, seven least-privilege roles, `CONNECT` revoked from `PUBLIC`; idempotent, re-applies passwords every start
- [x] SCRAM passwords required on every TCP connection, loopback included
- [x] Kafka 4.3.2 KRaft with internal (`kafka:29092`) and external (`localhost:9092`) listeners; auto-create off
- [x] kafka-init reconciles 14 topics (7 + DLTs) from `topics.json`; refuses partition changes, repairs retention
- [x] Redis 8 with password (not on the command line), AOF, `volatile-lru`; Mailpit
- [x] OTel Collector → Tempo / Loki / Prometheus; Grafana with provisioned data sources and infrastructure dashboard; infra alert rules
- [x] `scripts/dev-up`, `dev-down`, `check-infra` (bash + PowerShell wrappers); `.env` generated with random secrets
- [x] `check-infra`: 103 checks pass on fresh volumes and after a restart with kept data
- [x] Gate tests: password rotation, partition-change refusal, retention repair, port-conflict detection, ShellCheck clean, LF endings
- [x] Runbook `docs/runbooks/infra.md`; CI jobs `shell-lint` and `infra`
- [x] CI green on GitHub, including the `infra` job on Linux (run 41379890078)
- [x] Tag `m2-done`

## Milestone 3 — Identity, catalog, API gateway

- [x] `libs/security-tokens`: shared JWT rules (issuer, audience, roles) for servlet and reactive apps
- [x] `libs/web-common`: RFC 9457 errors, correlation ids, transactional audit writer, problem-shaped 401/403
- [x] identity-service: Argon2id, lockout, RS256 access tokens, rotating refresh tokens with family revocation, JWKS, admin bootstrap
- [x] catalog-service: products, Postgres full-text search, Redis cache-aside (ADR-0009), optimistic locking, admin API, uncached checkout prices
- [x] api-gateway: explicit routes, JWT and role checks, per-client rate limits with Retry-After, CORS, security headers, 1 MB body limit, actuator on an internal port
- [x] Tests: identity 5 unit + 20 integration, catalog 3 + 16, gateway 13; full build 215 tests
- [x] Five planted security regressions each caught by the intended test
- [x] Non-root images (uid 10001), layered jars; `dev-up --apps` builds and starts the services
- [x] `check-infra`: 117 checks including register → login → admin create → public search through the gateway
- [x] k6 smoke: 2,340 requests, 0 failures ([report](reports/m3-smoke-test.md))
- [x] gitleaks clean over all trackable files
- [ ] CI green on GitHub
- [ ] Tag `m3-done`

Found and fixed during this milestone:
- Hikari `connection-timeout: 3s` is not a valid value (it binds to milliseconds); the service would not start.
- `char(3)` currency column failed Hibernate schema validation; now `text` with a format check.
- Argon2 hashing ran inside the database transaction, holding a pooled connection for tens of milliseconds per login; moved out.
- The gateway's own health endpoint returned 401 on the management port, so its container never became healthy.
- Prometheus never loaded edited scrape config (bind mounts are not watched); `dev-up` now reloads it.
- `mvnw` failed when run from `dev-up`, because path conversion, disabled for docker, is needed by the Windows JVM.
- OpenAPI promised a breached-password check and a 429 body that do not exist; contracts corrected.
- A Redis-down test was weaker than its name; replaced with tests that pin the real library behaviour (ADR-0009).

## Milestones 4–12

Tracked here as each one starts. See [ROADMAP.md](ROADMAP.md) for scope and gates.

## Known limitations (kept honest)

- Local Kafka is a single broker with replication factor 1.
- One Postgres server is a shared failure domain locally (ADR-0006).
- Notification emails can be sent twice in a narrow crash window (ARCHITECTURE.md B.4).
