# CommerceFlow

A distributed, event-driven commerce platform built to show how checkout stays correct when services crash, messages are duplicated, and many buyers race for the last item, and to measure, not assume, how it behaves.

Seven Spring Boot services and a payment-provider simulator, talking over REST and Kafka, each with its own PostgreSQL database, behind one gateway, with a React web app on top. It runs on Docker Compose, on local Kubernetes (kind), and on k3s the way the optional AWS deployment would run it.

## What has been measured

Every number here links to the report that measured it, with its raw data.

- **Correctness under failure.** Nine failure scenarios ran against the real stack, 5 times in a row, with every business invariant intact: killed services, a stopped broker, a cut database link, every message delivered three times, and injected consumer faults. No oversold stock, no double charge, no lost order. [Failure recovery report](docs/reports/failure-recovery.md)
- **Read latency at 500 users.** The target was p95 under 300 ms for the catalogue and order reads, and it was met in all five runs: p95 188–284 ms, with 0 errors in about 1.86 million measured requests. Under a fast ramp-up the system was already overloaded at 500 users, by a sign-in surge, and throughput peaked at 750 users. [Performance report](docs/reports/performance.md)
- **The bottleneck, and its fix.** In 768 MB containers the JVM had chosen the Serial garbage collector by itself, and the gateway spent 21 % of its time stopped. With G1 that fell to 2.8 %, and the longest pause in any service fell from 2.87 s to 0.16 s. The report includes the cost: the median rose. [Performance report](docs/reports/performance.md#the-bottleneck-garbage-collection-in-the-gateway)
- **Kubernetes failures mid-checkout.** 90 checkouts ran while one pod hung and another was deleted. Every checkout was accepted once, with no duplicates from retries, and all were confirmed. A bad release was rolled back with 0 failed requests. [ADR-0015](docs/adr/0015-kubernetes-on-kind.md) · [Milestone 10 checklist](docs/CHECKLIST.md#milestone-10--kubernetes-on-kind)
- **Autoscaling.** Catalog went from 1 to 4 pods and back under a fixed 200 requests per second, with none of 52,499 requests failing. [HPA experiment](docs/reports/hpa-experiment.md)

## What it demonstrates

- **Saga orchestration** with compensating transactions across the order, inventory and payment services
- **Transactional outbox**, so a database commit and its Kafka event cannot diverge
- **Idempotency at every hop:** API idempotency keys, consumer deduplication, business-key uniqueness, provider idempotency keys
- **No overselling** under concurrent checkout: row locks taken in a fixed order, guarded updates, and constraints that reject any write creating or losing stock
- **Payment reconciliation** for timeouts and lost acknowledgements, against a self-hosted payment sandbox
- **Observability:** one trace per checkout across the services and Kafka, percentile dashboards, and alerts that are each tested firing and clearing, each linked to a runbook
- **Security tested, not just configured:** forged and expired tokens, cross-account access, replayed keys and refresh tokens, and personal data in logs and metrics, all checked against the running stack ([threat model](docs/security/SECURITY.md))
- **Kubernetes done carefully:** the restricted Pod Security level for every pod, readiness that checks only a pod's own database, memory limits the JVM sizes itself from, images tagged by commit ([ADR-0015](docs/adr/0015-kubernetes-on-kind.md))
- **Infrastructure as code without an account:** Terraform for an EC2 + k3s deployment and an EKS reference stack. Every stack is validated, linted, scanned and tested against mocked providers, and the instance's own deploy is tested on k3s in Docker ([ADR-0016](docs/adr/0016-terraform-and-aws-demo.md))

## Run it

Needs Java 21, Docker, and (on Windows) Git for Windows; Maven comes with the wrapper. [docs/SETUP.md](docs/SETUP.md) walks through it from a clean clone.

```bash
scripts/dev-up.sh --obs --apps   # everything; creates .env and a local JWT key on first run
scripts/check-infra.sh           # verifies every component, then a full flow through the gateway
```

Then the web app is at http://localhost:8000 and Grafana at http://localhost:3000. [docs/DEMO.md](docs/DEMO.md) is a ten-minute tour: a checkout followed live, a declined card compensated, a killed service recovered from, and the trace and dashboards behind it.

| Also | |
|---|---|
| `./mvnw verify` | build, unit, contract and integration tests |
| `scripts/e2e.sh` · `scripts/security-test.sh` | the browser smoke test · the security suite, against the running stack |
| `scripts/system-test.sh` | the failure-recovery scenarios |
| `scripts/perf.sh load` | the 500-user workload (`smoke`, `stress`, `soak` too) |
| `scripts/kind-up.sh` | the same system on local Kubernetes; `kind-chaos.sh`, `kind-rollback.sh`, `kind-hpa.sh` |
| `scripts/k3s-test.sh` · `scripts/check-terraform.sh` | the AWS deployment's deploy path on k3s, and its Terraform checks, without an account |

Deploying, upgrading and rolling back: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md). Troubleshooting: [runbooks](docs/runbooks/).

## Documentation

| | |
|---|---|
| [Architecture](docs/architecture/ARCHITECTURE.md) | services, the order lifecycle, events, schemas, invariants, failure modes |
| [Decisions](docs/adr/) | one ADR per decision, with the alternatives and the reasons |
| [API](docs/api/README.md) | the OpenAPI contracts and the rules every endpoint follows |
| [Reports](docs/reports/) | failure recovery, performance, the HPA experiment |
| [Security](docs/security/SECURITY.md) | the threat model, each threat with its control and its test |
| [Roadmap](docs/ROADMAP.md) · [Checklist](docs/CHECKLIST.md) | the twelve milestones, each review-and-fix gate, and what each one found |

## Tech

Java 21 · Spring Boot 4.1 · Spring Cloud Gateway · Spring Security · Spring Data JPA · Flyway · PostgreSQL · Apache Kafka (KRaft) · Redis · Resilience4j · OpenTelemetry · Prometheus · Grafana · Tempo · Loki · Testcontainers · k6 · React + TypeScript + Vite · Docker Compose · Kubernetes (kind, k3s) · Kustomize · Terraform · GitHub Actions
