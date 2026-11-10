# Setup: from a clean clone to a running stack

Everything runs on one machine, with free and self-hosted tools only.

## What you need

| | |
|---|---|
| Docker | Docker Desktop (Windows, macOS) or Docker Engine with Compose (Linux). Give it at least 12 GB of memory for the whole stack; 16 GB leaves room for the load tests |
| Java 21 | any distribution (Temurin is what CI uses). Maven is downloaded by the wrapper (`./mvnw`) |
| Git, and on Windows Git for Windows | the scripts are bash; Git for Windows includes bash and openssl. PowerShell versions of dev-up, dev-down and check-infra exist too |
| Optional | [kind](https://kind.sigs.k8s.io) for the Kubernetes setup (`winget install Kubernetes.kind` on Windows). kubectl comes with Docker Desktop. Node is not needed: the web app is built and tested in containers |

Free ports: 5432, 6379, 9092, 1025, 8025, plus 3000, 4317, 4318 and 9090 for observability, and 8000 and 8080–8085 for the services. `dev-up` checks them and says which one is taken.

## First run

```bash
git clone https://github.com/Kartavya1905/commerceflow.git
cd commerceflow
scripts/dev-up.sh --obs --apps
```

On the first run, `dev-up`:
1. creates `.env` from `.env.example`, with random local passwords (git-ignored);
2. creates a local JWT signing key in `secrets/` (git-ignored);
3. builds the service jars and images (a few minutes);
4. starts Postgres, Kafka, Redis and Mailpit, creates one database and role per service and the Kafka topics, then starts the observability stack and the services, and waits until every one is healthy.

Then check it:

```bash
scripts/check-infra.sh      # every component, then a full checkout through the gateway
```

| Where | What |
|---|---|
| http://localhost:8000 | the web app; the admin's sign-in is `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` in `.env` |
| http://localhost:8080 | the API gateway ([API reference](api/README.md)) |
| http://localhost:3000 | Grafana (`GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` in `.env`): the Checkout and Service latency dashboards, traces in Tempo, logs in Loki |
| http://localhost:8025 | Mailpit, the local mail catcher |

## Tests

```bash
./mvnw verify               # unit, contract and integration tests (Testcontainers, needs Docker)
scripts/e2e.sh              # the web app in a real browser, against the running stack
scripts/security-test.sh    # the security suite, against the running stack
scripts/system-test.sh      # the failure-recovery scenarios: start the stack with --apps --chaos first
```

## Stopping

```bash
scripts/dev-down.sh             # stop; data is kept
scripts/dev-down.sh --volumes   # stop and delete all local data
```

Something wrong: [runbooks/infra.md](runbooks/infra.md) for Compose, [runbooks/kubernetes.md](runbooks/kubernetes.md) for kind.
