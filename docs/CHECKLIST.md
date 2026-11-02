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

- [ ] Compose file with `core`, `apps`, `obs` profiles and pinned image tags
- [ ] Postgres init: seven databases, seven roles, `CONNECT` revoked from `PUBLIC`
- [ ] Kafka KRaft with internal and external listeners; topics created from `topics.json`
- [ ] Redis with password; Mailpit
- [ ] OTel Collector, Prometheus, Tempo, Loki, Grafana with provisioned data sources
- [ ] `scripts/dev-up` and `scripts/dev-down` (bash + PowerShell)
- [ ] Infra smoke test, including cross-database access refused

## Milestones 3–12

Tracked here as each one starts. See [ROADMAP.md](ROADMAP.md) for scope and gates.

## Known limitations (kept honest)

- Local Kafka is a single broker with replication factor 1.
- One Postgres server is a shared failure domain locally (ADR-0006).
- Notification emails can be sent twice in a narrow crash window (ARCHITECTURE.md B.4).
