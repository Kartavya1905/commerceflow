# ADR-0010: How the outbox is implemented

- Status: Accepted
- Date: 2026-11-02
- Refines: ADR-0004 (polling transactional outbox), ADR-0007 (blocking retries and DLT)

## Context
ADR-0004 chose a polling outbox with a single active publisher. Building it in Milestone 5 required several narrower decisions, each of which a reviewer can reasonably question.

## Decisions
1. **Validate at write time.** `OutboxWriter` checks every envelope against its JSON Schema before storing it. A violation throws and rolls back the business change too, so an invalid event can never be published. The cost (a few milliseconds per event) buys a guarantee that consumers never see a message they were not promised.
2. **Producers strict, consumers tolerant.** Consumers do not run the strict schema (which forbids unknown fields); they read only the envelope and the payload fields they use, so a producer can add optional fields without breaking them (ARCHITECTURE.md F.6).
3. **Writes require an existing transaction** (`Propagation.MANDATORY`). An outbox row written in its own transaction would reintroduce the dual write the outbox exists to remove. A test and a mutation check enforce this.
4. **Per-aggregate version from a counter table** (`aggregate_versions`), not from the outbox, because outbox rows are cleaned up. The upsert also row-locks the aggregate, so two writers of the same order are serialised and their outbox rows are in commit order.
5. **Leader per round, not per process.** Each publish round takes `pg_try_advisory_xact_lock`; an instance that does not get it skips the round. No long-held connection, and leadership moves automatically if an instance dies. The round's database transaction stays open while Kafka acknowledges (bounded by `delivery.timeout.ms`); it holds only the publisher's own connection.
6. **Ordering under failure relies on the idempotent producer.** All rows of a round are sent, then acknowledgements are awaited in order. With `enable.idempotence=true`, a failed record also fails later in-flight records on the same partition, so a later event for an order cannot be acknowledged ahead of an earlier one.
7. **Backlog metrics on their own thread.** During a Kafka outage the publisher blocks on acknowledgements; gauges refreshed only by it froze at their last value, exactly when the backlog grows. Found by a test in Milestone 5.
8. **UUID v7 event ids**, so outbox and inbox primary keys are time-ordered and index-friendly.

## Consequences
- At-least-once delivery with per-order ordering; duplicates are absorbed by the inbox (`processed_events`) in the consumer's transaction.
- Throughput is bounded by one publisher per service. Measured in Milestone 12.
- `traceparent` is not yet carried across Kafka: services have no tracing until Milestone 9. The correlation id and causation id are carried, and that is what ties a workflow together today.
