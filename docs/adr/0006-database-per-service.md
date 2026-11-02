# ADR-0006: Database per service on one PostgreSQL server

- Status: Accepted
- Date: 2026-11-01

## Context
The brief forbids one service reading or modifying another service's tables. Seven Postgres containers would cost several GB of RAM on a laptop.

## Decision
Locally, one PostgreSQL server hosts one database per service. Each service has its own login role that owns only its database, and `CONNECT` is revoked from `PUBLIC`. Each service runs its own Flyway migrations. Cross-service data is obtained only through APIs or events.

## Consequences
- Ownership is enforced by the database; a test in M2 proves it.
- One server is a shared failure domain locally. The failure tests treat "Postgres down" as affecting every service, which is the harder case.
- Moving a service to its own server later is a connection-string change.

## Alternatives
- Schema per service in one database: weaker isolation, easy to over-grant.
- One container per service: stronger isolation, too much memory for local development.
