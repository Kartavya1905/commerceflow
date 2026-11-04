# Runbook: local infrastructure

Commands assume the repository root. On Windows use Git Bash, or the `.ps1` wrappers in `scripts/`. `C` below stands for:

```bash
docker compose -f deploy/compose/docker-compose.yml --env-file .env
```

| Task | Command |
|---|---|
| Start core | `scripts/dev-up.sh` |
| Start core + observability | `scripts/dev-up.sh --obs` |
| Verify everything | `scripts/check-infra.sh` |
| Stop, keep data | `scripts/dev-down.sh` |
| Stop and delete all local data | `scripts/dev-down.sh --volumes` |
| Logs of one container | `C logs -f kafka` |
| SQL as a service role | `C run --rm --no-deps -e PGHOST=postgres -e PGPASSWORD=<from .env> --entrypoint psql postgres-init -U order_svc -d order_db` |
| List topics | `C exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list` |

## Running the services

- `scripts/dev-up.sh --apps` builds the jars on your machine, builds the images, and starts identity-service (:8081), catalog-service (:8082), order-service (:8083), inventory-service (:8084), payment-service (:8085), payment-sandbox (inside the network only) and api-gateway (:8080). Run it again after changing code; it rebuilds what changed.
- Use the gateway (http://localhost:8080) for normal calls. The service ports are published on localhost only for debugging.
- The first admin is created from `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` in `.env`, only if no admin exists yet.
- Non-browser clients get the refresh token in the response body by sending `X-Token-Delivery: body`; browsers get an HttpOnly cookie instead.
- The JWT signing key is `secrets/jwt-private.pem`, created by `dev-up`. Deleting it and running `dev-up` again rotates it: every existing token stops working, and services pick up the new key from the JWKS by its new key id.
- Logs are JSON in containers: `C --profile apps logs -f api-gateway`. Filter one request with its id: `... | grep <X-Correlation-Id>`.

### The web app

- http://localhost:8000 is the web app (`web`: nginx serving the build and proxying `/api` to the gateway). Sign in as the bootstrap admin to see the Admin pages.
- Checkout offers the sandbox's test cards. "Answers too late" (`tok_timeout`) shows the order processing for 30-40 s while the reconciler finds the charge, then confirmed.
- Signed out after every reload: the browser dropped the refresh cookie. It is `Secure`, which browsers keep over plain HTTP only on `localhost`, so use `http://localhost:8000`, not `127.0.0.1` or a machine name.
- Every visitor shares one rate-limit bucket (many 429s at once): the gateway is not trusting nginx's `X-Forwarded-For`. Check `GATEWAY_TRUSTED_PROXIES=web` on api-gateway, and that the `web` container is running (a name that does not resolve is not trusted).
- A blank page with a Content-Security-Policy error in the browser console means something inline crept into the build. The policy allows only files served by the app itself.
- Development without Docker for the app: `npm run dev` in `frontend/` (needs Node 22) serves on :5173 and proxies `/api` to the gateway on :8080. The smoke test: `scripts/e2e.sh`, against the running stack.

### A service is unhealthy

`C --profile apps ps` shows which. `C --profile apps logs <service> | grep -m5 -iE "error|exception|failed"` usually names the cause. Common ones:

| Symptom | Cause |
|---|---|
| `password authentication failed` | `.env` password changed but `dev-up` not re-run (postgres-init re-applies passwords) |
| `Cannot read the JWT private key` | `secrets/jwt-private.pem` missing; run `dev-up` |
| `Schema validation: wrong column type` | an entity and a Flyway migration disagree; fix the migration or the mapping, never turn validation off |
| gateway 401 on every protected call | identity-service is down, so the gateway cannot fetch the JWKS |
| 429 from the gateway | per-client rate limit; raise `GATEWAY_RATE` / `GATEWAY_AUTH_RATE` for load tests |

## Outbox backlog stuck

Alert `OutboxBacklogStuck`: a service has committed events that have not reached Kafka for over a minute. Nothing is lost (the events are safe in that service's `outbox_events` table), but everything downstream of them waits.

1. Is Kafka up? `C ps kafka`; Grafana → CommerceFlow / Messaging shows which service is stuck.
2. What does the publisher say? `C --profile apps logs <service> | grep -i outbox`. "did not acknowledge" means Kafka refused or timed out.
3. Inspect the stuck rows (as the service's role, see "SQL as a service role" above):
   `select seq, event_type, publish_attempts, last_error from outbox_events where published_at is null order by seq limit 20;`
4. Once Kafka is reachable the publisher drains the backlog by itself, in order. Do not edit or delete outbox rows by hand.

## Dead-letter messages

Alert `DeadLetterMessages`: a message failed every retry, or could never be processed (not JSON, unknown version), and was moved to `<topic>.dlt` so its partition could keep moving.

1. Read it, with the failure reason Spring Kafka attached as headers:
   `C exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic order.events.v1.dlt --from-beginning --property print.headers=true --property print.key=true --max-messages 5`
   The `kafka_dlt-exception-message` header says why.
2. Fix the cause (a bug, or a broken dependency that is now back).
   Or list it through the admin API: `scripts/replay-dlt.sh list order.events.v1.dlt` shows each record's key, event id and type, original topic and failure.
3. Replay it: `scripts/replay-dlt.sh replay <dlt-topic> <partition> <offset>`, or `replay-all <dlt-topic>`. A replay puts the record back on its original topic unchanged, so the normal consumer handles it with its retries and dedupe. Replaying twice, or replaying something that was meanwhile applied, changes nothing. Each replay is audited (`DLT_REPLAYED`).
4. Usually nothing is lost even without a replay: the saga sweeper re-sends the command of any step whose answer is late, so a parked answer is asked for again. A replay just gets there sooner.
5. A database outage never parks messages: consumers retry until the database is back (the partition waits, in order), so after an outage there should be nothing here.

## An order is stuck in checkout

An order stays `PENDING` or `PAYMENT_PROCESSING`. The saga knows why:

Most of the time nothing needs doing. The deadline sweeper (every 5 s) re-sends a late step's command up to 5 times, waiting a random, growing time between re-sends. Then it compensates: an order waiting for its reservation or its payment is released (and its payment cancelled), and fails. Alert `SagaStuck` fires only for a saga past its last re-send in a step with no safe way out: committing paid stock, releasing stock, or refunding.

1. `GET /api/v1/admin/sagas?stuck=true` (admin token, through the gateway) lists sagas past their deadline. `state` names the step it is waiting on, and `stepAttempts` how many re-sends it has had.
2. In `AWAITING_RECONCILIATION`, the payment's outcome is unknown (provider timeout or error), and `GET /api/v1/admin/payments?orderId=<id>` shows the attempt as `UNKNOWN`. The reconciler resolves it from the provider's records (see "Payments"). Never fail such an order by hand: it may have been paid for. If it is still unknown after 30 minutes, the saga cancels the payment, which refunds a charge it finds.
3. In any other waiting state, the answer it is waiting for has not arrived. Check the answering service is healthy and its outbox is draining ("Outbox backlog stuck" above), and whether anything for the order is in a dead-letter topic ("Dead-letter messages").
4. Do not edit `saga_instances` or `orders` by hand. The saga's row lock and guarded updates are what keep stock, money and status in step. The sweeper takes the same row lock, so it never acts on a saga while an event is being applied to it.
5. `SagaSweepFailing`: sweeping one saga threw (its log line names the order and the cause) and was rolled back. The others are still swept.

## Payments

- payment-service never calls the provider inside a database transaction, and each order has at most one payment attempt, whose id is the provider's idempotency key. `payment_attempts` is the record. In the sandbox, `charges.idempotency_key` is that same attempt id, so the two can be compared directly.
- `UNKNOWN`: the provider did not answer usably. `REFUND_PENDING`: money was taken for an order that is being cancelled, and the refund is in progress (retried with the same key until it succeeds).
- payment-service logs `Charge for attempt … unresolved (PROVIDER_TIMEOUT)` for each timeout. Many in a row usually mean the sandbox is down or a fault is switched on. Reset it from inside the network (payment-service's container has the key): `C --profile apps exec payment-service sh -c 'curl -s -X POST -H "Authorization: Bearer $SANDBOX_API_KEY" -H "Content-Type: application/json" -d "{\"mode\":\"NONE\"}" http://payment-sandbox:8099/sandbox/faults'`.
- Test tokens: `tok_success`, `tok_decline`, `tok_insufficient`, `tok_timeout` (charges, then answers too late), `tok_unknown` (fails without charging).
- The reconciler (every 10 s) asks the provider, by idempotency key, about `UNKNOWN` attempts, `INITIATED` ones older than 30 s (left by a crash), and `REFUND_PENDING` ones whose refund call failed. A charge it finds is recorded exactly as if the call had answered. With no charge for 15 minutes, the attempt fails (`TIMEOUT_UNRESOLVED`) and the stock is released. Attempts closed without a charge get one later look, and a charge that landed after all is refunded (alert `LateChargeRefunded`).
- `PaymentReconciliationOverdue`: an outcome has been unknown for over 15 minutes, so the provider cannot be asked. Check the sandbox is up and not in a fault mode (above). `payment.reconcile.provider_unavailable` counts the failed lookups.
- "Reconcile now": the admin payments page (or `POST /api/v1/admin/payments/{attemptId}/reconcile`) brings an attempt's next look forward to the reconciler's next round, within 10 s, for example once the sandbox is back. A settled attempt answers 409: there is nothing left to find. Each request is audited (`PAYMENT_RECONCILE_REQUESTED`).

## Following one request

Every log line carries `traceId`, `spanId` and `correlationId`, and with `--obs` every request is traced: one trace covers a checkout across the gateway, the services and each Kafka hop.

1. From a problem response, take the correlation id; from a log line, the `traceId`.
2. Grafana → Explore → Tempo: search by trace id, or with TraceQL, for example `{ resource.service.name = "payment-service" && status = error }`.
3. Without `--obs` nothing is exported (`TRACING_EXPORT` is off). The ids are still in the logs: `C --profile apps logs <service> | grep <traceId>`.

## Security scans

`scripts/scan-security.sh` (CI: the `security` workflow, plus an image scan in `ci`):

- **gitleaks** finds a secret in the git history: rotate it first (it is public once pushed), then remove it from history if it matters, and allowlist only a value that is provably not a secret, in `.gitleaks.toml` with the reason.
- **Trivy** finds a HIGH or CRITICAL vulnerability with a fix: upgrade. For a library that comes in transitively, pin the fixed version in the parent pom's `dependencyManagement`, with the CVE, and remove the pin once the direct dependency moves. For an image, rebuild on a patched base. Accept a finding only in `.trivyignore`, with the reason and a review date.
- **CodeQL** results are on GitHub under Security → Code scanning.
- **The security suite** (`scripts/security-test.sh`) failing on logs or metrics means personal data reached a log line or an id became a metric label. Find the line it names, fix the cause, and do not widen the patterns.

## Fault injection

Alert `FaultInjectionOn`: a service is failing consumer records on purpose. That is the failure-recovery tests' switch, `commerceflow.messaging.chaos.*`, which `deploy/compose/chaos.yml` wires to `CHAOS_CONSUMER_DELAY` and `CHAOS_CONSUMER_FAILURE_RATE`. It must never be on outside a test. Recreate the services without those variables: `COMMERCEFLOW_CHAOS=1 scripts/dev-up.sh --apps --chaos`, from a shell where neither is set.

## Running the failure-recovery tests

1. `scripts/dev-up.sh --apps --chaos` starts the stack with Toxiproxy in front of order-service's database. Nothing is broken until a test does it.
2. `scripts/system-test.sh 5` runs every scenario five times. It kills containers, stops Kafka, cuts the database link and injects faults, so do not use the stack meanwhile.
3. Results are in `system-tests/target/failure-recovery/results.jsonl`, one line per scenario and run. `docs/reports/failure-recovery.md` is written from them.
4. If a run is interrupted, put things back: `scripts/dev-up.sh --apps --chaos` restarts anything stopped and recreates the services with fault injection off. The sandbox fault and the Toxiproxy link are reset by the next run's setup.
5. To stop a run, stop the test JVM too, not only the script and Maven. Failsafe forks it (`surefirebooter-*.jar`), and it outlives its parent and keeps killing containers. Two runs at once against one stack produce failures that mean nothing. On Windows, find it with `Get-CimInstance Win32_Process -Filter "name='java.exe'"` and check its parent is the system-tests Maven process before stopping it. Then reset as in step 4 and count runs from 1 again.

## Server errors

Alert `ServerErrors`: more than 5 % of a service's requests ended in `500` for 5 minutes. A `500` is a bug: a lost database answers `503` with `Retry-After`, and has its own alerts.

1. Grafana → CommerceFlow / Service latency → "Server errors" shows which service, route and status.
2. The service's log has the exception at ERROR (`Unhandled exception`), with the correlation id the client got in its problem response. Search the logs for that id: `C --profile apps logs <service> | grep <correlationId>`.
3. Since Milestone 9 each log line also carries a trace id. With `--obs`, Grafana → Explore → Tempo finds the whole request across services by that id.

## Slow reads

Alert `SlowReads`: the 95th percentile of a catalogue or order read has been above 300 ms for 10 minutes (NFR-05).

1. Grafana → CommerceFlow / Service latency, p95 and p99 by route. One route or all? All at once usually means the host or the database is overloaded (`docker stats`).
2. Catalogue reads are served from Redis when warm; a cold or unreachable cache sends every read to Postgres. Check Redis is healthy.
3. A trace of a slow request (Tempo, `--obs`) shows where the time went: the database, the cache, or another service.

## Consumer lag growing

Alert `ConsumerLagGrowing`: a consumer group is more than 100 messages behind for 5 minutes.

1. Grafana → Infrastructure overview → consumer lag shows which group and topic.
2. Is the service healthy? Slow handlers usually mean a slow database: check its logs for timeouts.
3. A message that keeps failing does not cause lasting lag: after its retries it goes to the dead-letter topic and the partition moves on.

## Config changes do not take effect

Config files under `deploy/compose/` are bind-mounted, and `docker compose up` does not restart a container when only a mounted file changed. `dev-up --obs` reloads Prometheus automatically. For anything else: `C --profile obs restart <service>`.

## Port already in use

`dev-up` checks ports before starting. Find the program holding the port and stop it:

- Windows: `netstat -ano | findstr :5432`, then stop that PID in Task Manager. A locally installed PostgreSQL service is the usual culprit; stop it in `services.msc`.
- Linux/macOS: `lsof -i :5432`.

## Scrape target down

Alert `ScrapeTargetDown`: Prometheus could not scrape a job for one minute.

1. Open http://localhost:9090/targets and read `lastError`.
2. `C ps` to see whether the container is running; `C logs <service>` for why it stopped.
3. `connection refused` usually means the container restarted; `context deadline exceeded` means it is overloaded. Check memory in `docker stats`.

## Kafka broker unreachable

Alert `KafkaBrokerUnreachable`: kafka-exporter sees no broker.

1. `C ps kafka`: is it healthy?
2. `C logs kafka | tail -50`. A message about `CLUSTER_ID` or a corrupt log directory after an unclean shutdown can be fixed by deleting the Kafka volume (all local messages are lost): `scripts/dev-down.sh --volumes`.
3. A process on your machine (not in a container) must use `localhost:9092`; containers must use `kafka:29092`. Mixing them up gives "connection refused" or a hang after the first metadata request.

## Kafka topic missing

Alert `KafkaTopicMissing`, or a service logs `UNKNOWN_TOPIC_OR_PARTITION`.

1. `C logs kafka-init`: kafka-init creates topics from `contracts/src/main/resources/events/topics.json`.
2. Re-run it: `C run --rm kafka-init`.
3. Auto-creation is deliberately disabled, so a misspelled topic name in code fails loudly instead of creating a stray topic. Fix the name, not the broker setting.

## kafka-init refuses a partition change

`ERROR <topic> has N partitions, catalogue says M` means `topics.json` changed a partition count. kafka-init will not change it, because Kafka maps keys to partitions by count: changing it sends new messages for an existing order to a different partition, so they can be consumed out of order. Locally, recreate the cluster with `scripts/dev-down.sh --volumes`. In a real environment this needs a migration plan (new topic version, dual publish).

## Postgres: password authentication failed

1. The service must read the password for **its own** role from `.env` (`ORDER_DB_PASSWORD` for `order_svc`, and so on).
2. Changing a password in `.env` takes effect on the next `scripts/dev-up.sh`, because postgres-init re-applies passwords on every start.
3. `permission denied for database` is intentional when a role connects to a database it does not own (ADR-0006).

## Postgres: loopback check fails in check-infra

The Postgres volume was created before `--auth-host=scram-sha-256` was added, so it still trusts loopback TCP inside the container. Recreate it with `scripts/dev-down.sh --volumes`.

## Grafana login

User `admin` (or `GRAFANA_ADMIN_USER`), password `GRAFANA_ADMIN_PASSWORD` from `.env`. Grafana stores the admin password in its volume on first start, so changing `.env` later has no effect until you run `scripts/dev-down.sh --volumes` or reset it with `C exec grafana grafana cli admin reset-admin-password <new>`.

## Data that does not survive a container rebuild

Tempo traces and Loki logs are kept inside their containers (24 h and 72 h retention) and are lost when those containers are recreated. Postgres, Kafka, Redis, Prometheus and Grafana use named volumes and survive `dev-down` without `--volumes`.
