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
3. Replay tooling arrives in Milestone 7. Until then, a message can be re-sent to its original topic by hand; consumers are idempotent, so replaying something already applied is harmless.

## An order is stuck in checkout

An order stays `PENDING` or `PAYMENT_PROCESSING`. The saga knows why:

1. `GET /api/v1/admin/sagas?stuck=true` (admin token, through the gateway) lists sagas past their deadline. `state` names the step it is waiting on.
2. In `AWAITING_RECONCILIATION`, the payment's outcome is unknown (provider timeout or error), and `GET /api/v1/admin/payments?orderId=<id>` shows the attempt as `UNKNOWN`. This is deliberate. The charge may have happened, so the stock stays held until the outcome is known. The reconciler that resolves it arrives in Milestone 7. Never fail such an order by hand: it may have been paid for.
3. In any other waiting state, the answer it is waiting for has not arrived. Check the answering service is healthy and its outbox is draining ("Outbox backlog stuck" above), and that nothing for the order is in a dead-letter topic.
4. Until the sweeper (Milestone 7), a lost answer is not re-requested automatically. Do not edit `saga_instances` or `orders` by hand. The saga's row lock and guarded updates are what keep stock, money and status in step.

## Payments

- payment-service never calls the provider inside a database transaction, and each order has at most one payment attempt, whose id is the provider's idempotency key. `payment_attempts` is the record. In the sandbox, `charges.idempotency_key` is that same attempt id, so the two can be compared directly.
- `UNKNOWN`: the provider did not answer usably. `REFUND_PENDING`: money was taken for an order that is being cancelled, and the refund is in progress (retried with the same key until it succeeds).
- payment-service logs `Charge for attempt … unresolved (PROVIDER_TIMEOUT)` for each timeout. Many in a row usually mean the sandbox is down or a fault is switched on. Reset it from inside the network (payment-service's container has the key): `C --profile apps exec payment-service sh -c 'curl -s -X POST -H "Authorization: Bearer $SANDBOX_API_KEY" -H "Content-Type: application/json" -d "{\"mode\":\"NONE\"}" http://payment-sandbox:8099/sandbox/faults'`.
- Test tokens: `tok_success`, `tok_decline`, `tok_insufficient`, `tok_timeout` (charges, then answers too late), `tok_unknown` (fails without charging).

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
