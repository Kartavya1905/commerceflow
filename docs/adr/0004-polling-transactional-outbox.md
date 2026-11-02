# ADR-0004: Polling transactional outbox with a single active publisher

- Status: Accepted
- Date: 2026-11-01

## Context
A service must change its database and publish an event as one logical step. A database commit and a Kafka send cannot be made atomic: "commit then send" loses events on a crash, and "send then commit" publishes events for changes that never happened.

## Decision
Write the event to `outbox_events` in the same database transaction as the business change. A publisher in the same service polls unpublished rows ordered by `(created_at, id)`, sends them with an idempotent producer (`acks=all`), and marks them published after the broker acknowledges. Only one publisher per service is active at a time, elected with `pg_try_advisory_lock`. Trace context is stored in a `headers` column and restored on send.

## Consequences
- At-least-once publishing: a crash after the broker ack and before marking the row produces a duplicate, which consumers absorb with `processed_events`.
- Per-aggregate ordering is preserved because one publisher sends rows in commit order.
- Throughput is bounded by one publisher per service. Acceptable at this scale; measured in M12.
- Polling adds up to about 200 ms latency per hop when idle.

## Alternatives
- Debezium CDC on the Postgres WAL: lower latency and no polling, but needs Kafka Connect and logical replication. Documented as the upgrade path.
- Kafka transactions: only cover Kafka-to-Kafka writes; they do not help with a Postgres commit.
- Parallel publishers with `SKIP LOCKED`: higher throughput, but breaks per-order ordering.
