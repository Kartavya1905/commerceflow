# CommerceFlow

A distributed, event-driven commerce platform built to show how checkout stays correct when services crash, messages are duplicated, and many buyers race for the last item.

> **Status: Milestone 1 of 12.** Architecture, contracts and build skeleton are done; services arrive from Milestone 3. Nothing below is a measured result yet. Performance and recovery numbers will appear here only once a report in `docs/reports/` backs them.

## What it demonstrates

- **Saga orchestration** with compensating transactions across order, inventory and payment services
- **Transactional outbox** so a database commit and its Kafka event cannot diverge
- **Idempotency at every hop**: API idempotency keys, consumer dedupe, business-key uniqueness, provider idempotency keys
- **No overselling** under concurrent checkout, enforced by a single conditional update and database constraints
- **Payment reconciliation** for timeouts and lost acknowledgements, against a self-hosted payment sandbox
- **Observability**: traces across services and Kafka, consumer lag, outbox age, stuck-saga alerts

## Architecture

Seven Spring Boot services plus a payment-provider simulator, communicating over REST and Apache Kafka, each with its own PostgreSQL database. See [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) for diagrams, the order lifecycle, event definitions, schemas and invariants, and [docs/adr](docs/adr) for the reasoning behind each decision.

## Build

Requires Java 21 and Docker. Maven is downloaded by the wrapper.

```bash
./mvnw verify
```

On Windows PowerShell use `.\mvnw.cmd verify`.

## Project plan

[docs/ROADMAP.md](docs/ROADMAP.md) lists the twelve milestones and the review-and-fix gate each one must pass. [docs/CHECKLIST.md](docs/CHECKLIST.md) tracks progress.

## Tech

Java 21 · Spring Boot 4.1 · Spring Cloud Gateway · Spring Security · Spring Data JPA · Flyway · PostgreSQL · Apache Kafka (KRaft) · Redis · Resilience4j · OpenTelemetry · Prometheus · Grafana · Tempo · Loki · Testcontainers · k6 · React + TypeScript + Vite · Docker Compose · Kubernetes (kind) · Terraform · GitHub Actions
