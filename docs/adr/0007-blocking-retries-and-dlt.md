# ADR-0007: Blocking consumer retries with jitter, then a dead-letter topic

- Status: Accepted
- Date: 2026-11-01

## Context
Consumers hit transient errors (a database blip) and permanent ones (a malformed message). Retries must be bounded, must not reorder messages for the same order, and must not block a partition forever.

## Decision
Spring Kafka `DefaultErrorHandler` with jittered exponential backoff (200 ms to 10 s) and 6 attempts on the same partition, then `DeadLetterPublishingRecoverer` to `<topic>.dlt`. Deserialisation and schema errors are non-retryable and go to the DLT immediately. DLT records can be listed and replayed through the normal consumer path.

## Consequences
- Per-key order is preserved during retries.
- A failing record delays the rest of its partition by at most about 30 seconds.
- Saga deadlines catch any order whose message ended up in the DLT.

## Alternatives
- Non-blocking retry topics (`@RetryableTopic`): keep partitions moving but let later events for the same order overtake the failing one, which the saga would then have to treat as out of order.
