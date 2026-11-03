# ADR-0012: Recovery, reconciliation and failure testing

- Status: Accepted
- Date: 2026-11-02
- Refines: ADR-0007 (blocking retries and DLT), ADR-0011 (saga and payment implementation)

## Context
Milestone 6 recorded deadlines and unknown payment outcomes but left nothing to act on them. An order whose answer was lost waited forever, and an `UNKNOWN` payment held its stock until a person looked. Milestone 7 makes every accepted order finish without manual steps (INV-6), and proves it against the running stack.

## Decisions

### Retries
1. **Full jitter everywhere a crowd retries.** `Backoff.fullJitter` waits a random time between zero and `min(max, base·2ⁿ)`. The Kafka error handler, the saga sweeper and the payment reconciler all use it, so callers that failed together during an outage come back spread out.
2. **An unreachable database is retried without limit; a bad message is not.** `Outages.isOutage` recognises a missing connection anywhere in the cause chain (`DataAccessResourceFailureException`, `CannotCreateTransactionException`, `SQLTransientConnectionException`). Those records are retried, backoff capped at 30 s, until the database answers, with the partition waiting in order. Anything else gets the bounded 6 attempts, then the dead-letter topic. An outage therefore parks nothing.
3. **Fault injection lives in the consumer path, off by default.** `commerceflow.messaging.chaos.consumer-delay` and `consumer-failure-rate` act before the handler and its transaction, so an injected failure has nothing to undo and takes the normal retry path. They log a warning at startup, count `messaging_chaos_failures_total`, and raise `FaultInjectionOn`. Only `deploy/compose/chaos.yml` wires them up.

### Saga deadline sweeper (order-service)
4. **Each state has one step command**, the one every transition into it sends (`SagaTransitions.stepCommand`, checked against the table). The sweeper re-sends it with a new event id but the same request (OrderCreated is rebuilt from the saved order), up to `saga-max-resends` (5). Each receiver answers a repeat from what it already decided.
5. **Then the deadline transition, if there is one.** Waiting for a reservation or a payment gives up by compensating: release, or cancel the payment, whether or not the step happened, because both are no-ops when there is nothing to undo. Committing paid stock, releasing and refunding have no safe way out, so they keep being re-sent at the capped interval, and `saga_stuck` raises `SagaStuck`. A test checks that every unfinished state has a move, and that every deadline transition compensates.
6. **Reconciliation gets a deadline transition too**: `AWAITING_RECONCILIATION` + deadline → `CANCELLING_PAYMENT`. The reconciler resolves well inside its 15-minute window, so the 30-minute saga deadline is only a backstop. Cancelling an unknown attempt looks the charge up first, then refunds or voids it.
7. **The sweeper takes the saga row with `FOR UPDATE SKIP LOCKED`**, the same row the event handlers lock. One saga is never swept and applied at once. A saga that is busy is left for the next round. Each saga is swept in its own transaction, and one that throws is rolled back and counted without stopping the round.
8. **The reason sent with a compensating command is stored** (`saga_instances.command_reason`, V4), so a re-sent `ReleaseInventory` or `CancelPayment` says what the first one said.

### Payment reconciler (payment-service)
9. **The reconciler asks the provider, by idempotency key, and records what it says through the same guarded updates as the charge path.** A charge found becomes `SUCCEEDED`, a decline `FAILED`. If a late provider answer or a redelivered command resolves the attempt first, the second write changes nothing (tested by racing them on ten attempts).
10. **An attempt is claimed by moving its next look forward**, with backoff, before the provider is asked. A crash or a failed lookup just leaves it due again later, and a second reconciler instance finds it not due.
11. **What it looks at**: `UNKNOWN` attempts when due; `INITIATED` ones older than 30 s (no live call takes that long, so a crash left them); `REFUND_PENDING` ones whose refund call failed. Nothing else retried a refund that began from a late charge, which was a gap in Milestone 6.
12. **No charge for the whole window (15 min) means failed** (`TIMEOUT_UNRESOLVED`), and the saga releases the stock.
13. **Closed attempts get one later look.** An attempt closed without a charge, by a cancel or by decision 12, is looked at once more, 30 s later. A charge that landed after all is refunded. This closes ADR-0011's open gap: a crash after a cancel found no charge, while a charge request was still on its way. For the same reason a late success on a `FAILED` attempt is now refunded like one on a `CANCELLED` attempt. A decline cannot become a success under the same key.

### Dead letters, shutdown and tests
14. **Replay hands the record back to its own consumer.** `DeadLetters` (libs/messaging) lists a dead-letter topic and re-publishes one record unchanged (key, envelope, event id, original headers) to its original topic. Dedupe and business-key idempotency then make a second replay, or a replay of something already applied, a no-op. order-service exposes it to admins (`/api/v1/admin/dlt/{topic}`), audits every replay, and `scripts/replay-dlt.sh` drives it. Only `*.dlt` topics can be read or replayed.
15. **Shutdown order**: listener containers stop first and finish their current record, the outbox publisher next (phase below them, so it still sends what they wrote), and the connection pool last. Compose gives containers 40 s before SIGKILL, above the services' 30 s shutdown phase.
16. **Failure tests run against the real stack**, not containers in a test JVM. They kill, stop, pause and cut the link with Docker and Toxiproxy, then check INV-1..INV-6 from the databases. The `system-tests` module sits outside the default build (`-Psystem-tests`). The sandbox's `FAIL_ALL` fault now fails lookups and refunds too, so "provider down" means down.

17. **A lost database is `503` with `Retry-After`** (`ProblemDetailsAdvice`), for a missing connection and for a rollback that failed on a connection that died mid-request. Nothing was committed, so the client may retry with the same Idempotency-Key. The consumers' outage check (decision 2) treats the failed rollback the same way.
18. **The gateway caches a service's address for at most 5 s** (`UpstreamDnsConfig`). Netty would otherwise keep Docker's DNS answer for its 600 s TTL, and a recreated container is on a new address.

## Consequences
- Re-sent `OrderCreated` events reach every consumer of `order.events.v1`. The notification service (Milestone 9) must treat a repeated order id as the same order, not send a second confirmation.
- A customer whose charge lands after their order failed is charged and refunded. That is correct, but each case shows on `LateChargeRefunded`.
- Under heavy injected faults, an order can fail by timing out (and be compensated) where a healthy system would have confirmed it. The tests accept either outcome there, and check that the invariants hold.
- Re-sends and replays add Kafka traffic that is harmless but not free. `saga.resends` and the DLT replay audit trail show how much.
