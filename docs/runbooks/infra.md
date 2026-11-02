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
