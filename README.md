# CommerceFlow

A distributed, event-driven commerce platform built to show how checkout stays correct when services crash, messages are duplicated, and many buyers race for the last item.

> **Status: Milestone 6 of 12.** The whole checkout runs locally as an orchestrated saga over Kafka. An order reserves stock, is charged once by a self-hosted payment sandbox, and has its stock committed (CONFIRMED). If a step fails, the earlier steps are undone: a declined card releases the stock, and a failed commit refunds the charge and then releases. A payment whose outcome is unknown is held, never failed. Automatic recovery of stuck steps (deadline sweeper, payment reconciler) arrives in Milestone 7. Nothing below is a measured result yet. Performance and recovery numbers will appear here only once a report in `docs/reports/` backs them.

## What it demonstrates

- **Saga orchestration** with compensating transactions across order, inventory and payment services
- **Transactional outbox** so a database commit and its Kafka event cannot diverge
- **Idempotency at every hop**: API idempotency keys, consumer dedupe, business-key uniqueness, provider idempotency keys
- **No overselling** under concurrent checkout: row locks taken in a fixed order, guarded updates, and database constraints that reject any write creating or losing stock
- **Payment reconciliation** for timeouts and lost acknowledgements, against a self-hosted payment sandbox
- **Observability**: traces across services and Kafka, consumer lag, outbox age, stuck-saga alerts

## Architecture

Seven Spring Boot services plus a payment-provider simulator, communicating over REST and Apache Kafka, each with its own PostgreSQL database. See [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) for diagrams, the order lifecycle, event definitions, schemas and invariants, and [docs/adr](docs/adr) for the reasoning behind each decision.

## Run it locally

Requires Java 21, Docker, and (on Windows) Git for Windows. Maven is downloaded by the wrapper.

```bash
scripts/dev-up.sh --obs --apps   # everything; creates .env and a local JWT key on first run
scripts/check-infra.sh           # verifies every component, then a full flow through the gateway
./mvnw verify                    # build, unit, contract and integration tests (needs Docker)
scripts/dev-down.sh              # stop (add --volumes to delete local data)
```

On Windows PowerShell use `.\scripts\dev-up.ps1 --obs --apps`, `.\scripts\check-infra.ps1` and `.\mvnw.cmd verify`. The API is at http://localhost:8080 (admin login: `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` in `.env`), Grafana at http://localhost:3000, Mailpit at http://localhost:8025. Troubleshooting: [docs/runbooks/infra.md](docs/runbooks/infra.md).

## Project plan

[docs/ROADMAP.md](docs/ROADMAP.md) lists the twelve milestones and the review-and-fix gate each one must pass. [docs/CHECKLIST.md](docs/CHECKLIST.md) tracks progress.

## Tech

Java 21 · Spring Boot 4.1 · Spring Cloud Gateway · Spring Security · Spring Data JPA · Flyway · PostgreSQL · Apache Kafka (KRaft) · Redis · Resilience4j · OpenTelemetry · Prometheus · Grafana · Tempo · Loki · Testcontainers · k6 · React + TypeScript + Vite · Docker Compose · Kubernetes (kind) · Terraform · GitHub Actions
