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
- [ ] First push to a public GitHub repository; CI green there
- [ ] Tag `m1-done`

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
- [ ] CI green on GitHub (needs the first push)
- [ ] Tag `m2-done`

## Milestones 3–12

Tracked here as each one starts. See [ROADMAP.md](ROADMAP.md) for scope and gates.

## Known limitations (kept honest)

- Local Kafka is a single broker with replication factor 1.
- One Postgres server is a shared failure domain locally (ADR-0006).
- Notification emails can be sent twice in a narrow crash window (ARCHITECTURE.md B.4).
