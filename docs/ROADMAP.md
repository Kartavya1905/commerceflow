# CommerceFlow — Delivery Roadmap

Twelve milestones, each ending in a **review-and-fix gate**. A milestone is done only when its acceptance criteria pass on a clean clone *and* every item in its gate has been checked and either fixed or written up as a known limitation. Progress is tracked in [CHECKLIST.md](CHECKLIST.md); decisions in [adr/](adr).

Architecture reference: [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md).

---

## How every phase ends: the exit gate

Run this at the end of **every** milestone, before tagging it.

1. **Clean-clone build.** `git clone` into a new folder, `cp .env.example .env`, run the milestone's commands. If something only works on your machine, it is not done.
2. **Full test suite.** `./mvnw -B verify` (plus milestone-specific suites). Zero failures, zero skipped tests without a linked reason.
3. **Code review pass**, against the milestone's gate below and this standing list:
   - Every `@Transactional` method is called through the Spring proxy (not `this.method()`), and does no network I/O while holding DB locks.
   - Every catch block either handles, rethrows with context, or records a metric. No swallowed exceptions.
   - Every external call has a timeout.
   - No secret, token, password, or card-like value in code, config, logs, or test fixtures.
   - Every new table has a primary key, the constraints its invariants need, and an index for each query in the code.
   - Every new endpoint has validation, an authorisation rule, and a negative test.
4. **Fix, then re-run 1–2.** Each fix gets a regression test that failed before the fix.
5. **Update docs.** ADR for any decision made; CHECKLIST ticked; known limitations recorded.
6. **Tag.** `git tag m<N>-done` on the commit that passed.

Do not start milestone N+1 while milestone N has a failing test.

---

## Milestone 1 — Requirements, architecture, contracts, repository

| | |
|---|---|
| **Goal** | Everything later milestones depend on is decided and machine-checkable: architecture, invariants, event schemas, topic catalogue, REST contracts, build skeleton |
| **Prerequisites** | Java 21, Docker Desktop, Git. Maven is provided by the wrapper |
| **Decisions** | ADR-0001 to ADR-0008 (Spring Boot 4.1, orchestrated saga, polling outbox, JSON Schema, per-service DBs, blocking retries, self-hosted payment sandbox) |
| **Steps** | 1. Repo skeleton and parent POM. 2. Envelope + 19 event schemas. 3. Topic catalogue. 4. OpenAPI for identity, catalog, order, inventory, payment. 5. Valid and invalid examples. 6. Contract tests. 7. CI workflow that runs them |
| **Files** | `pom.xml`, `contracts/**`, `docs/**`, `.github/workflows/ci.yml`, `.env.example` |
| **Commands** | `./mvnw -B verify` |
| **Tests** | Schemas are valid 2020-12; every valid example passes; every invalid example fails for the stated reason; topic catalogue ↔ schemas ↔ examples are consistent; OpenAPI files parse with no errors |
| **Acceptance** | `./mvnw -B verify` passes from a clean clone; CI is green on the first push |

**Review-and-fix gate — what to look for**

| Likely defect | How to detect | Fix |
|---|---|---|
| Money modelled as `number` | grep schemas for `"type": "number"` | `integer` minor units + ISO 4217 currency |
| Payment schemas accept extra fields, so a card number could slip through | invalid example with `cardNumber` must fail | `additionalProperties: false` on producer schemas |
| Event without `aggregateVersion` or `correlationId` | envelope `required` list | add to envelope; every event `$ref`s the envelope |
| Topic lists an event with no schema, or a schema used by no topic | `TopicCatalogConsistencyTest` | fix the catalogue |
| Enum values differ between OpenAPI and event schemas (e.g. failure reasons) | compare by hand once; later a test | single source in the event schema |
| Contract tests pass because the example directory is empty | test asserts at least one example per event type | — |

---

## Milestone 2 — Local infrastructure with Docker Compose

| | |
|---|---|
| **Goal** | `scripts/dev-up` starts Postgres (seven databases, seven roles), Kafka (KRaft), Redis, Mailpit, and the observability stack; topics are created from `topics.json` |
| **Prerequisites** | M1 |
| **Decisions** | Exact image tags pinned (no `latest`); core services have no profile and always start, `obs` and `apps` are profiles (a profile-only core broke `depends_on` across profiles); one Postgres server with per-service DBs; init jobs run on every start and are idempotent; all ports bound to 127.0.0.1 |
| **Steps** | 1. `deploy/compose/docker-compose.yml` with health checks. 2. `init/01-databases.sh` creating DBs and roles from env vars, revoking `CONNECT` from `PUBLIC`. 3. Kafka topic-init container reading `topics.json`. 4. OTel Collector, Prometheus, Tempo, Loki, Grafana with provisioned data sources. 5. `scripts/dev-up` / `dev-down` (bash and PowerShell) |
| **Commands** | `./scripts/dev-up core obs`, `docker compose ps`, `./scripts/check-infra` |
| **Tests** | `InfraSmokeIT` (Testcontainers Compose module, or a script): each role can connect to its own DB and is **refused** on every other DB; all topics exist with the right partition count; Redis `PING`; Grafana data sources healthy |
| **Acceptance** | Fresh `dev-up` is healthy in < 3 minutes; cross-database access test proves isolation |

**Review-and-fix gate**

| Likely defect | How to detect | Fix |
|---|---|---|
| Kafka advertised listener is `localhost`, so containers cannot reach it (or the host cannot) | a container and the host both produce a message | two listeners: `INTERNAL://kafka:29092`, `EXTERNAL://localhost:9092` |
| Init SQL runs only on first volume creation, so changes are silently ignored | change a role, re-run, it is not applied | document `dev-down --volumes`; make init idempotent |
| Passwords in `docker-compose.yml` | grep for `PASSWORD:` values | `${VAR:?}` from `.env`, which is git-ignored |
| `PUBLIC` still has `CONNECT`, so isolation is fake | the cross-DB test | `REVOKE CONNECT ON DATABASE … FROM PUBLIC` |
| Containers start before dependencies are ready | flaky first boot | `depends_on: condition: service_healthy` with real health checks |
| Windows line endings break shell scripts in containers | `/bin/sh^M: bad interpreter` | `.gitattributes`: `*.sh text eol=lf` |

---

## Milestone 3 — Identity, catalog, API gateway

| | |
|---|---|
| **Goal** | Register, log in, refresh, log out; browse and search products; admins manage products; all through the gateway with rate limits and correlation IDs |
| **Prerequisites** | M2 |
| **Decisions** | RS256 JWT, JWKS from identity; Argon2id; access 15 min, refresh 7 days rotating; Postgres FTS; Redis cache-aside; RFC 9457 errors; `libs/web-common` |
| **Steps** | 1. `libs/web-common`: problem+json handler, correlation filter, JSON logging. 2. identity-service with Flyway V1, `users`, `refresh_tokens`, `audit_events`. 3. Key-pair loading from env/secret, JWKS endpoint, key id. 4. catalog-service with products, search, cache, admin API. 5. Gateway routes, JWT validation, `/admin/**` role check, Redis rate limiter, security headers |
| **Tests** | Unit: password policy, token service. Integration (Testcontainers Postgres + Redis): register/login/refresh/reuse detection, lockout, search ranking, cache eviction on update. Security: no token → 401; customer on admin route → 403 at gateway **and** at service when called directly; expired, wrong-key, `alg: none` tokens rejected; SQL-meta characters in search are harmless; oversize bodies rejected; rate limit returns 429 with `Retry-After` |
| **Acceptance** | All of the above green; k6 smoke (10 VUs, 1 min) on `GET /products` without errors |

**Review-and-fix gate**

| Likely defect | How to detect | Fix |
|---|---|---|
| Login error says "user not found" vs "wrong password" (account enumeration) | test both cases return identical body and similar timing | one generic message; hash a dummy password when user missing |
| Refresh token stored in plain text | inspect `refresh_tokens` | store SHA-256 hash only |
| Refresh-token reuse does not revoke the family | test: use an old token after rotation | revoke every token with the same `family_id` |
| Services trust the gateway and skip their own auth | call service port directly with a customer token on admin API | resource-server config in every service |
| Search built with string concatenation | grep for `"+` in repository queries | `websearch_to_tsquery(:q)` with a bound parameter |
| Cache serves stale prices after an admin update | test update → read | evict in the same method after commit (`@TransactionalEventListener(AFTER_COMMIT)`) |
| Private key committed for "local convenience" | gitleaks | generate at `dev-up` into a git-ignored path |
| Lost update on concurrent admin edits | two edits with same version | `@Version` → `409` |

---

## Milestone 4 — Orders and inventory reservation (no Kafka yet)

| | |
|---|---|
| **Goal** | Correct, concurrency-safe core domain in each service before adding messaging: cart, idempotent order creation with price snapshot, atomic reservation, commit, release |
| **Prerequisites** | M3 |
| **Decisions** | Conditional `UPDATE` for reservation; lines sorted by product id; `UNIQUE(customer_id, idempotency_key)` + request hash; cart in Redis with 7-day TTL; order → catalog price lookup with timeout and circuit breaker |
| **Steps** | 1. inventory schema and `ReservationService` (reserve all-or-nothing, commit, release, all idempotent by order id). 2. Admin stock adjustments with audit. 3. order schema, cart API, `POST /orders` → `202`. 4. Owner-scoped order queries |
| **Tests** | `LastUnitRaceIT` (100 concurrent reservations, stock 1 → exactly one success, repeated 20×); multi-line deadlock test (two orders with lines in opposite order, 200 iterations, no deadlock); reserve twice for same order → one reservation; release twice → stock restored once; same idempotency key + same body → same order; same key + different body → `422`; concurrent duplicate submissions → one order; customer B reading A's order → `404`; catalog down → `503 PRICE_UNAVAILABLE` within timeout |
| **Acceptance** | All green; INV-1, INV-2, INV-7 asserted after every test |

**Review-and-fix gate**

| Likely defect | How to detect | Fix |
|---|---|---|
| Read-then-write (`findById`, check, `save`) oversells under load | `LastUnitRaceIT` | single conditional `UPDATE`, check affected rows |
| Deadlocks on multi-item orders | deadlock test, Postgres log `deadlock detected` | sort lines by product id before updating |
| Duplicate submission race creates two orders | concurrent duplicate test | rely on the unique constraint; catch violation, re-read, compare hash |
| Request hash depends on JSON field order | same body with reordered fields gives `422` | hash a canonical form (sorted items, normalised) |
| Order total computed from client-sent prices | test with a tampered price | prices only from catalog |
| `findById(orderId)` without owner check | cross-account test | `findByIdAndCustomerId` |
| Catalog call made inside the order transaction (holds a DB connection during network I/O) | read the code; pool exhaustion under k6 | fetch prices before opening the transaction |

---

## Milestone 5 — Kafka integration and transactional outbox

| | |
|---|---|
| **Goal** | Events leave each service exactly when its DB transaction commits, reach Kafka at-least-once, and are consumed idempotently |
| **Prerequisites** | M4 |
| **Decisions** | Polling publisher with advisory-lock leader; `processed_events` in the consumer's transaction; blocking retry + DLT; trace headers stored in outbox |
| **Steps** | 1. `libs/messaging`: envelope type, `EventPublisher` (writes outbox), `IdempotentConsumer` helper, error handler, DLT naming. 2. `libs/outbox`: entity, publisher loop, cleanup. 3. Producer-side contract test: every event a service writes validates against its schema. 4. Wire `OrderCreated` → inventory reservation → `InventoryReserved/Rejected` → order status update |
| **Tests** | Business rollback ⇒ no outbox row; commit ⇒ row; publisher crash between send and mark ⇒ duplicate on topic, consumer applies once; Kafka down (Testcontainers pause) ⇒ outbox grows, drains on resume, order preserved per key; poison message ⇒ DLT after bounded retries, partition keeps moving; trace id survives the outbox hop |
| **Acceptance** | Order created through the gateway reaches `PAYMENT_PROCESSING` or `FAILED/OUT_OF_STOCK` via Kafka; all tests green |

**Review-and-fix gate**

| Likely defect | How to detect | Fix |
|---|---|---|
| Event sent with `KafkaTemplate` inside the business method (dual write) | grep for `kafkaTemplate.send` outside the publisher | only the publisher sends |
| Outbox insert in a different transaction (self-invocation, `REQUIRES_NEW`) | rollback test | same transaction, call through the proxy |
| Offset committed before DB commit (auto-commit on) | crash-after-poll test | `enable.auto.commit=false`, ack mode `RECORD` |
| Dedupe check and side effect in separate transactions | kill between them | one transaction; insert into `processed_events` first and let the PK conflict abort |
| Two publisher instances interleave, breaking per-order order | run two instances in a test | advisory-lock leader |
| Publisher hot-loops when Kafka is down | CPU during outage | backoff on failure |
| Unbounded `SELECT` of unpublished rows after a long outage | 100 k-row backlog test | batch `LIMIT` |
| Trace broken at the outbox | Tempo shows two traces | restore `traceparent` from the `headers` column |

---

## Milestone 6 — Saga orchestration and payment sandbox

| | |
|---|---|
| **Goal** | The full checkout workflow from section E of the architecture, with the payment sandbox and compensation |
| **Prerequisites** | M5 |
| **Decisions** | Orchestrated saga in order-service; explicit transition table; write-ahead payment attempts; sandbox as its own service with its own DB; `CancelPayment` tombstone |
| **Steps** | 1. payment-sandbox: charges and refunds with idempotency keys, magic tokens (`tok_success`, `tok_decline`, `tok_insufficient`, `tok_timeout`, `tok_unknown`), fault-injection admin API. 2. payment-service: `AuthorizePayment` handler, provider client with timeout and circuit breaker, `CancelPayment` (void-or-refund). 3. Saga: transition table, handlers, commit/release commands. 4. `OrderConfirmed` / `OrderFailed` |
| **Tests** | Unit: every (state, event) pair in the transition table, including invalid ones → ignored. Integration: happy path; decline → released + `FAILED`; insufficient funds; commit failure → refund → release; `CancelPayment` before `AuthorizePayment` arrives → no charge ever; duplicate `AuthorizePayment` → one charge; every terminal state checks INV-3, INV-4, INV-5 |
| **Acceptance** | All paths green end to end with real Kafka and Postgres containers |

**Review-and-fix gate**

| Likely defect | How to detect | Fix |
|---|---|---|
| Provider called while holding a DB transaction | code read; pool exhaustion when sandbox is slow | TX1 insert attempt → commit → call → TX2 record result |
| Idempotency key generated per HTTP retry instead of per attempt | sandbox shows two charges after a retry | key = `payment_attempts.id`, created once |
| Saga transition written as scattered `if` statements | transition-table unit test cannot be written | explicit `Map<(State, EventType), Transition>` |
| Invalid-for-state event throws, so it retries forever and lands in DLT | send a duplicate late event | acknowledge and count, do not throw |
| Order status and saga state updated in different transactions | crash test between them | one transaction per transition |
| Timeout treated as decline (releases stock for an order that was actually charged) | sandbox `tok_timeout` that later succeeds | timeout ⇒ `UNKNOWN`, never `FAILED` |

---

## Milestone 7 — Failure recovery, retries, reconciliation, idempotency

| | |
|---|---|
| **Goal** | Every failure scenario in the brief has an automated test with stated expected state, and the system recovers without manual steps |
| **Prerequisites** | M6 |
| **Decisions** | Saga deadline sweeper; payment reconciler; exponential backoff with full jitter; DLT replay tool; graceful shutdown settings |
| **Steps** | 1. Saga sweeper with bounded re-sends then fail-safe compensation. 2. Reconciler for `UNKNOWN` and stale `INITIATED`. 3. DLT list/replay admin endpoints and `scripts/replay-dlt`. 4. `system-tests` module driving the running Compose stack with `docker kill`, `docker pause`, Toxiproxy. 5. Runbooks |
| **Tests** (each asserts INV-1..INV-6 at the end) | Kill inventory mid-checkout; restart payment after charge before ack; deliver every event 3×; 100 simultaneous orders for one unit; disconnect Postgres 30 s; stop Kafka 60 s; replay topics from offset 0; inject 5 s consumer delay and 30 % processing failures |
| **Acceptance** | All scenarios pass 5 consecutive runs; results written to `docs/reports/failure-recovery.md` with real numbers |

**Review-and-fix gate**

| Likely defect | How to detect | Fix |
|---|---|---|
| Retries without jitter cause synchronized retry storms | metrics during recovery show spikes | full jitter |
| Unbounded retries hide a permanent failure | scenario never ends | max attempts → compensation or DLT + alert |
| Reconciler and late provider response both resolve the same attempt | race test | conditional `UPDATE … WHERE status IN ('UNKNOWN','INITIATED')` |
| Sweeper and event handler process the same saga concurrently | race test | both take the saga row lock |
| Replay of a DLT message bypasses dedupe | replay twice | replay goes through the normal consumer |
| Shutdown kills in-flight records | `docker stop` during load | graceful shutdown timeout, container stop before DB pool close |
| Flaky tests "fixed" with `Thread.sleep` | grep | Awaitility with a stated bound |

---

## Milestone 8 — Frontend (customer and admin)

| | |
|---|---|
| **Goal** | Functional, not fancy: browse/search, cart, checkout with sandbox token picker, order history with live status, admin products/stock/orders/stuck-sagas/DLT pages |
| **Prerequisites** | M3 for browse and auth screens; M7 for checkout and admin ops |
| **Decisions** | React + TypeScript + Vite; generated API client from OpenAPI; access token in memory, refresh token in an `HttpOnly; Secure; SameSite=Strict` cookie set by identity via the gateway |
| **Tests** | Vitest component tests for cart and checkout state; Playwright smoke: register → buy → see `CONFIRMED`; admin pages hidden for customers **and** API returns 403 |
| **Acceptance** | Smoke test green in CI against Compose |

**Review-and-fix gate**: tokens in `localStorage` (XSS exposure) → in memory + cookie; a new `Idempotency-Key` generated on every click (double-submit creates two orders) → one key per checkout attempt, kept until a terminal response; status polling with no backoff → exponential backoff; admin routes only hidden in the UI → verified 403 from API.

---

## Milestone 9 — Testing, security, observability

| | |
|---|---|
| **Goal** | Close coverage gaps, add security scanning and tests, make failures visible on dashboards and alerts |
| **Prerequisites** | M7 |
| **Steps** | 1. JaCoCo thresholds. 2. ArchUnit rules (no cross-service imports, controllers do not touch repositories directly). 3. Trivy (fs + images), gitleaks, CodeQL workflows. 4. Security test suite: authz bypass, invalid input, replay of old access/refresh tokens and idempotency keys, cross-account access. 5. Dashboards: checkout funnel, saga states, outbox age, consumer lag, DLT count, reservation failures, payment reconciliation backlog, p95 latency per route. 6. Alert rules with runbook links. 7. `docs/security/SECURITY.md` threat model |
| **Acceptance** | Coverage thresholds met; no untriaged high/critical findings; each alert fires in a scripted test and clears after recovery |

**Review-and-fix gate**: high-cardinality metric labels (order id as a label) → only bounded labels; PII in logs (email, token) → redaction + a test that scans captured logs; dashboards that show averages only → percentiles and rates; alerts without a runbook → link required.

---

## Milestone 10 — Kubernetes on kind

| | |
|---|---|
| **Goal** | Same system on local Kubernetes with Kustomize: Deployments, Services, ConfigMaps, Secrets, probes, resource limits, PodDisruptionBudgets, HPA experiment |
| **Prerequisites** | M9 |
| **Decisions** | kind with `extraPortMappings` and a NodePort for the gateway (no ingress controller needed); single-broker Kafka StatefulSet in the kind overlay; metrics-server for HPA; `kubeconform` for validation |
| **Tests** | Smoke suite against kind; kill a pod during checkout; HPA scales catalog under k6 load and scales back; `kubectl rollout undo` restores previous image |
| **Acceptance** | `scripts/kind-up` → smoke green; rollback demonstrated; HPA experiment written up with real numbers |

**Review-and-fix gate**: readiness probe that checks Kafka and Postgres (one broker blip takes every pod out) → readiness checks only what the pod needs to serve, liveness checks only the process; no resource limits → JVM uses node memory → set limits and `MaxRAMPercentage`; Secrets committed as base64 → generated locally, never in Git; `latest` image tags → immutable tags by commit SHA.

---

## Milestone 11 — Terraform and optional AWS deployment

| | |
|---|---|
| **Goal** | Terraform for the recommended single-EC2 + k3s demo environment, validated in CI. Applying it is optional and needs your explicit approval |
| **Prerequisites** | M10 |
| **Decisions** | See architecture section K; GitHub OIDC federation to an IAM role (no long-lived keys); public subnet, no NAT, no ALB; SSM Session Manager instead of SSH |
| **Steps** | 1. `modules/network`, `modules/ec2-k3s`, `modules/iam-github-oidc`. 2. `envs/demo-ec2`. 3. `envs/reference-eks` (validate-only). 4. `fmt`, `validate`, `tflint`, `checkov` in CI. 5. Apply/destroy runbook with cost per hour |
| **Acceptance** | CI validates both stacks. If you approve an apply: deploy, smoke test, and `terraform destroy` leaves zero billable resources, confirmed in the console |

**Review-and-fix gate**: security group open to `0.0.0.0/0` → your IP only; IAM `*` actions → least privilege; Terraform state with secrets committed → remote or git-ignored local state; forgetting to destroy → runbook step and a scheduled reminder; resources outside Terraform → none allowed.

---

## Milestone 12 — Performance validation, documentation, release

| | |
|---|---|
| **Goal** | Real measurements, honest reports, and a portfolio-ready repository |
| **Prerequisites** | M9, M10 (M11 optional) |
| **Steps** | 1. k6 workloads: smoke, load (500 VUs), stress (ramp to failure), soak (30 min). 2. Record hardware, data set size, warm-up, and raw summaries. 3. Find and fix the top bottleneck; re-measure. 4. Final README, demo script, API docs, setup guide, deployment and rollback guide. 5. Resume bullets and interview questions based only on measured results |
| **Acceptance** | Every number in the README links to a report; nothing claimed that was not measured |

**Review-and-fix gate**: k6 running on the same machine as the system (it competes for CPU) → note it, or run k6 on a separate machine/CI runner; measuring a cold JVM → warm-up stage excluded from results; reporting averages → p50/p95/p99 and error rate; tuning the test until it passes → report the first failing result too.
