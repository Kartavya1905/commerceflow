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
- [x] CI green on GitHub (run 41402508616; the first run 41401290712 failed in the build job for a reason its logs, which need a login, did not reveal; it passed locally, in a Linux container and on re-run, so a flaky test is the likely cause. CI now publishes failures as annotations so the next one is diagnosable)
- [x] Tag `m3-done`

Found and fixed during this milestone:
- Hikari `connection-timeout: 3s` is not a valid value (it binds to milliseconds); the service would not start.
- `char(3)` currency column failed Hibernate schema validation; now `text` with a format check.
- Argon2 hashing ran inside the database transaction, holding a pooled connection for tens of milliseconds per login; moved out.
- The gateway's own health endpoint returned 401 on the management port, so its container never became healthy.
- Prometheus never loaded edited scrape config (bind mounts are not watched); `dev-up` now reloads it.
- `mvnw` failed when run from `dev-up`, because path conversion, disabled for docker, is needed by the Windows JVM.
- OpenAPI promised a breached-password check and a 429 body that do not exist; contracts corrected.
- A Redis-down test was weaker than its name; replaced with tests that pin the real library behaviour (ADR-0009).

## Milestone 4 — Orders and inventory reservation

- [x] inventory-service: stock with conservation enforced by the database (INV-1, INV-2), per-order reservation claim, all-or-nothing reserve, commit, release, release-before-reserve tombstone, long-stop expiry one order per transaction, audited admin adjustments, public availability
- [x] order-service: Redis cart (7-day TTL, 50 products), idempotent `POST /orders` per customer and key with a canonical request hash, prices only from catalog via timeouts and a circuit breaker, outside the DB transaction; owner-scoped history; admin view
- [x] `libs/test-support`: shared token minting and containers (catalog moved onto it)
- [x] Gateway routes for cart, orders, inventory; compose, Prometheus and check-infra cover five services
- [x] Tests: inventory 37 (last-unit race 100 buyers x 20 runs, 200 opposite-order runs, duplicate deliveries, every lifecycle transition), order 20 (idempotency races, ownership, catalog failure, slow catalog, circuit open and recovery)
- [x] `check-infra`: 126 checks, including restock → cart → order → replay → ownership through the gateway
- [x] Mutation checks: 5 of 6 regressions caught (see below)
- [x] CI green on GitHub (run 41409570005, first attempt)
- [x] Tag `m4-done`

Found during this milestone:
- A test helper created the circuit breaker with library defaults (open after 100 calls) before the service could configure it; the outage test caught it. Production code never looks breakers up by name.
- A `check-infra` step passed by comparing two empty order ids, because a temp file was written where bash could not read it; now no temp file, and ids must be non-empty.
- Mutation that removed the product-id sort before locking **survived**: with all of an order's rows locked in one statement, PostgreSQL's scan order is already consistent when plans match, as they do on small test tables. The `ORDER BY` stays so lock order does not depend on the query plan; no test here can force two different plans.
- OpenAPI listed an inventory `sku` the service does not have and only one of four 422 causes; corrected.

## Milestone 5 — Kafka integration and transactional outbox

- [x] `libs/messaging`: contract-validated outbox writes (MANDATORY transaction), per-aggregate versions, ordered publisher with a per-round advisory-lock leader and backoff, inbox dedupe in the consumer's transaction, blocking retries with jitter then `<topic>.dlt`, poison messages straight to DLT, correlation and causation ids carried across Kafka (ADR-0010)
- [x] inventory-service consumes `OrderCreated` and saga commands (commit, release) and answers through its outbox; re-sent requests get the stored answer
- [x] order-service writes `OrderCreated` in the order's transaction and moves orders on inventory's answer, guarded so duplicates and late events change nothing
- [x] Messaging dashboard; alerts for stuck outbox, dead letters and consumer lag, with runbook entries
- [x] Tests: messaging 10 (rollback, contract, order, redelivery, retry, DLT, poison once, outage, leader lock), inventory 43, order 23
- [x] `check-infra`: 131 checks, including an order reserved via Kafka (PAYMENT_PROCESSING) and an oversized one failing as OUT_OF_STOCK
- [x] Mutation checks: 7 of 7 caught
- [x] CI green on GitHub (run 41414057200, first attempt)
- [x] Tag `m5-done` (on 124ad45)

Found during this milestone:
- The outbox backlog gauge froze during a Kafka outage, because the blocked publisher was the only thing refreshing it; an alert on it would never have fired. Now refreshed on its own thread.
- Two guarantees had no test that could fail: a second publisher standing aside, and poison messages skipping retries (they reach the DLT either way). Both now have one, and the mutations that break them are caught.
- A compose edit put `KAFKA_BOOTSTRAP` into postgres-init instead of the services; `docker compose config` caught the duplicate key.

Not done yet, deliberately: catalog `ProductCreated`/`ProductUpdated` and identity `UserRegistered` are still not published (their consumers arrive with notification-service); `traceparent` across Kafka waits for tracing in Milestone 9.

## Milestone 6 — Saga orchestration and payment sandbox

- [x] payment-sandbox: its own database; charges and full refunds, idempotent by key (including concurrent requests); outcome tokens `tok_success`, `tok_decline`, `tok_insufficient`, `tok_timeout` (charges, then answers too late), `tok_unknown` (fails without charging); fault modes `FAIL_ALL` and `CHARGE_THEN_STALL`; API key required
- [x] payment-service: one attempt per order; write-ahead TX1, provider call with no transaction open (enforced), TX2; timeout or error means UNKNOWN plus `PaymentPendingReconciliation`, never FAILED; CancelPayment is void-or-refund with a tombstone; a charge that lands after its cancel is refunded; circuit breaker; admin read API (ADR-0011)
- [x] order-service saga: explicit transition table, one transaction per transition with the saga row locked, invalid-for-state events counted and acknowledged, `OrderConfirmed` / `OrderFailed`, admin saga list, zero-total orders refused
- [x] Gateway route for admin payments; Compose, Prometheus and dev-up wired for both new services
- [x] Tests: saga table 108 (state, event) pairs plus graph properties (no FAILED holding money, no COMPLETED without it, every FAILED has a reason); order-service integration 32 (happy path, decline, insufficient funds, commit failure → refund → release, unknown payment held, late events acknowledged, a failed transition leaves nothing behind); payment-service 15 against the real sandbox process (one charge under duplicates, CancelPayment before AuthorizePayment charges nothing, timeout is UNKNOWN, crash after the charge heals without a second one, late charge refunded, open circuit skips the provider); sandbox 11. 448 in the build
- [x] `check-infra`: 142 checks, now through the whole checkout. `tok_success` ends CONFIRMED with exactly one charge, `tok_decline` ends PAYMENT_DECLINED with its stock released and nothing charged, and `tok_timeout` is held as UNKNOWN with its stock kept. Cross-checked in the databases: no idempotency key charged twice, every CONFIRMED order has its charge, no FAILED order holds money
- [x] Mutation checks: 8 of 8 caught (after fixing the two tests below)
- [x] CI green on GitHub (run 41486171252, first attempt)
- [x] Tag `m6-done` (on 411ea20)

Found during this milestone:
- A late AuthorizePayment that ignored the cancellation tombstone charged nothing, but crashed into the dead-letter topic, leaving the saga without an answer. The test only checked that the answers it got were CANCELLED, not that both arrived, so the mutation survived. It now requires both, and two other tests got the same fix.
- The "invalid events are acknowledged, not thrown" test watched the dead-letter topic for 2 s, shorter than the retries take to get there, and its counter check passed because the counter ticks on every retry. It now checks that each event's dedupe record was committed, which only an acknowledged event gets.
- Catalog allows free products, but a zero total cannot be charged or confirmed under the event contracts. Such orders are now refused at placement (422 `zero-total`).
- The four orders left PENDING or PAYMENT_PROCESSING by earlier milestones' runs were given saga rows by the migration, with deadlines already due, for the Milestone 7 sweeper.

Not done yet, deliberately: the deadline sweeper (re-sends, then compensation) and the payment reconciler are Milestone 7. Until then, an order whose payment is UNKNOWN stays PAYMENT_PROCESSING with its stock held, and a lost answer is not re-requested.

## Milestone 7 — Failure recovery, retries, reconciliation

- [x] Retries: full jitter in the Kafka error handler, the sweeper and the reconciler. An unreachable database (including a failed rollback on a dead connection) is retried without limit, so an outage parks nothing in a dead-letter topic. A consumer fault-injection switch (delay, failure rate) is off by default (ADR-0012)
- [x] Saga deadline sweeper: every 5 s, `SKIP LOCKED`. Re-sends the step's command (OrderCreated rebuilt from the saved order) up to 5 times, then compensates. Steps with no safe way out keep re-sending and count as stuck. Reconciliation cancels at its 30-minute deadline. Stored command reasons (V4)
- [x] Payment reconciler: asks the provider by idempotency key about `UNKNOWN`, stale `INITIATED` and `REFUND_PENDING` attempts. A charge found is recorded through the same guarded updates as the charge path. No charge within 15 minutes means `TIMEOUT_UNRESOLVED`. Attempts closed without a charge get one later look, and a charge that landed late is refunded
- [x] Dead letters: `GET /api/v1/admin/dlt/{topic}` and `POST …/replay` (admins only, audited, `*.dlt` topics only). A replay goes back through the normal consumer and its dedupe. `scripts/replay-dlt.sh` (list, replay, replay-all)
- [x] Shutdown: listeners stop, then the outbox publisher, then the connection pool. Compose `stop_grace_period: 40s`. payment-sandbox drains gracefully too
- [x] HTTP: a lost database is `503` with `Retry-After`, never `500`. The gateway re-resolves service addresses every 5 s instead of trusting Docker's 600 s DNS TTL
- [x] Alerts: `SagaStuck`, `SagaSweepFailing`, `PaymentReconciliationOverdue`, `LateChargeRefunded`, `FaultInjectionOn` (promtool: 11 rules)
- [x] `system-tests` module (`-Psystem-tests`), `deploy/compose/chaos.yml` (Toxiproxy in front of order-service's database, chaos variables), `scripts/dev-up.sh --chaos`, `scripts/system-test.sh`. Nine scenarios, each ending with INV-1..INV-6 checked in the databases
- [x] All nine scenarios pass 5 consecutive runs (45 of 45, 0 invariant violations, 2026-11-03); numbers in [failure-recovery.md](reports/failure-recovery.md), raw results in [reports/data/](reports/data/failure-recovery-2026-11-03.jsonl)
- [x] Tests: saga table 129 (now including the deadline transitions and the sweeper always having a move); order-service integration 44 (sweeper 9, dead-letter replay 3); payment-service 26 against the real sandbox (reconciler 11); messaging 23 (backoff, outage detection including a failed rollback, chaos, outage retried past the limit, shutdown order); web-common 2 (503 mapping); gateway: the DNS cache cap, and the dead-letter route in GatewayIT. 497 in the build (448 after Milestone 6). `check-infra`: 144 checks, now including the reconciler confirming a `tok_timeout` order
- [x] Mutation checks: 11 of 11 caught (no jitter, never giving up, unguarded settle, sweeping without the row lock, replay bypassing dedupe, publisher stopping first, outage dead-lettered, late charge on a failed attempt kept, no look after a cancel, pending refund never retried, never resolving as unresolved)
- [x] CI green on GitHub (run 41660152488, first attempt, on 9a1c1cf, which contains this milestone; the commit alone also built locally: 497 tests, 0 failures)
- [x] Tag `m7-done` (on 40da9c4)

Found during this milestone:
- A system-test error message echoed the sandbox API key, which went to local logs only. The fault switch now goes in on stdin and reads the key inside the container, failures are redacted, and the logs were deleted.
- With the database cut, order placement answered `500`, not `503`. The mapping covered missing connections, but not a statement that failed mid-request followed by a rollback on the same dead connection, whose exception hid the first. Both HTTP and the consumers' outage check now recognise it. Without the fix, a consumer would have dead-lettered messages during an outage.
- The gateway kept sending requests to a recreated container's old address for up to 10 minutes (Netty caches DNS for Docker's 600 s TTL). Capped at 5 s.
- `dev-up` could ship a service with stale libraries: Maven does not re-create a jar whose own classes did not change, and Spring Boot then skips repackaging it. The build now forces jar creation.
- The sandbox's `FAIL_ALL` fault failed charges but not lookups or refunds, so "provider down" was not down. A refund whose call failed stayed `REFUND_PENDING` with nothing to retry it. The outbox publisher stopped before the listeners on shutdown, so their last events waited for the next start.
- The replay check proved only that lag returned to zero. It now also waits until the consumers have handled as many records as were replayed. Replaying "everything from offset 0" also replayed earlier replays, so the topics doubled every run: 95 k, then 199 k, 413 k and 856 k records, until the fifth run could not finish in 5 minutes. No record was applied twice at any size, but a test that grows without bound proves nothing about the next run. The scenario now replays every original record of its run, from the offsets the run started at, and marks its copies so they are never replayed again.
- Stopping a system-test run by its script or Maven left the forked test JVM running. It overlapped the next run and invalidated it. The runbook now says how to stop one, and only runs from a clean start count.

## Milestone 8 — Frontend (customer and admin)

- [x] `frontend/`: React 19 + TypeScript + Vite. Customer pages: browse and search, product with live stock, cart, checkout with a sandbox test-card picker, order history, and an order page that follows the saga live. Admin pages: orders by status, products and stock (create, restock, write off, archive), sagas past their deadline, payment attempts, and dead letters with replay (ADR-0013)
- [x] Typed client generated from `contracts/` (`openapi-typescript` + `openapi-fetch`). CI regenerates it and fails on a difference. The dead-letter endpoints were added to the order-service contract
- [x] Tokens: the access token is kept in memory only. The refresh token is the HttpOnly, SameSite=Strict cookie, restored on reload by one shared refresh. ESLint forbids web storage in app code
- [x] Checkout: one Idempotency-Key per attempt, kept across retries until a terminal answer. Order status polls with exponential backoff and jitter (1 s → 15 s) and stops when final
- [x] Served by nginx at http://localhost:8000 (unprivileged, strict CSP with no `'unsafe-inline'`, `frame-ancestors 'none'`), proxying `/api` to the gateway so the browser sees one origin, with no CORS
- [x] Gateway: believes `X-Forwarded-For` only from its trusted proxy (`web`), and then only the right-most hop. Without that, every visitor behind nginx shared one rate-limit bucket, login included
- [x] Tests: Vitest 33 (checkout state and page, cart total, session and refresh, polling, admin visibility); Playwright smoke 4 against Compose (register → buy → `CONFIRMED`; reload keeps the session, with nothing in web storage; admin pages hidden from a customer and the API answering 403; admin sees them); gateway `ClientAddressTest` 7. 504 in the Maven build
- [x] Mutation checks: 6 of 6 caught (new key on retry, key per click, token in localStorage, fixed-rate polling, admin pages shown to customers, parallel refreshes)
- [x] No Node on the machine: npm, Vitest and the build run in `node:22.23.4-alpine3.24`, and Playwright in its pinned image on the host network (`scripts/e2e.sh`)
- [x] CI green on GitHub, including the smoke test in the `infra` job (run 41660152488, first attempt)
- [x] Tag `m8-done` (on 9a1c1cf)

Found during this milestone:
- Behind nginx, the gateway answered every browser request with 403. nginx forwarded `Host` without its port, so the gateway took the app's own same-origin calls for cross-origin ones from a site it does not allow. nginx now forwards the browser's `Host`; a foreign origin is still refused.
- After a reload, "Add to cart" sent a signed-in customer to sign in: the page acted before the session had been restored. The button now waits until it is.
- Playwright's failure traces in `frontend/test-results/` recorded test tokens and the admin's sign-in request, and git did not ignore them. They are ignored now, the old ones were deleted, and none were ever committed.
- Two smoke tests reused the account the first test registers, so one failure took all three down. Each now has its own customer.

Added after the tag: `POST /api/v1/admin/payments/{attemptId}/reconcile` ("reconcile now"), in the contract since Milestone 6 but never built. It brings an attempt's next look forward to the reconciler's next round (within 10 s), so the look still goes through the usual claim and guarded updates. 202, or 409 for a settled attempt, 404 for none; every request audited (payment-service gets `audit_events`, V3). A "Reconcile now" button on the admin payments page shows only where there is something to look for. Tests: `ReconcileNowIT` 5 (including asking twice resolving once), Vitest 2; mutation check (settled attempt made reconcilable) caught

## Milestones 9–12

Tracked here as each one starts. See [ROADMAP.md](ROADMAP.md) for scope and gates.

## Known limitations (kept honest)

- Local Kafka is a single broker with replication factor 1.
- One Postgres server is a shared failure domain locally (ADR-0006).
- Notification emails can be sent twice in a narrow crash window (ARCHITECTURE.md B.4).
