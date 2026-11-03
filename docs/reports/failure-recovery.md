# Milestone 7: failure recovery against the running stack

**What this is:** nine failure scenarios run against the real Compose stack. Each kills a container, stops Kafka, cuts a database link, duplicates or replays messages, or injects consumer faults. Then the business invariants from ARCHITECTURE.md B.3 are checked in the databases. The acceptance bar from ROADMAP.md is five consecutive passing runs. **What this is not:** a performance or capacity result. Everything ran on one laptop, and the times below are recovery times on that machine, not production numbers.

## Setup

| | |
|---|---|
| Date | 2026-11-03, 03:59–04:42 UTC: five consecutive runs |
| Tests | [`system-tests/…/FailureRecoveryIT.java`](../../system-tests/src/test/java/dev/commerceflow/systemtests/FailureRecoveryIT.java), run by [`scripts/system-test.sh 5`](../../scripts/system-test.sh) |
| Stack | `scripts/dev-up.sh --apps --chaos`: the Compose stack plus [`deploy/compose/chaos.yml`](../../deploy/compose/chaos.yml) (Toxiproxy in front of order-service's database; consumer fault injection wired up, off until a scenario turns it on) |
| Checks after every scenario | INV-1 and INV-2 for all stock; for every order the run placed so far, INV-3 (one attempt, at most one successful charge at the provider), INV-4 (CONFIRMED has a held charge and committed stock), INV-5 (FAILED holds no money and no stock), INV-6 (terminal) |
| Raw results | [`data/failure-recovery-2026-11-03.jsonl`](data/failure-recovery-2026-11-03.jsonl): one line per scenario and run. The tables below are computed from it |
| Hardware | Windows 11 laptop, 10 logical CPUs, 31 GB RAM, Docker Desktop. Test driver, services, Kafka, Postgres and Toxiproxy all on the same machine |
| Settings | defaults from the services' `application.yml`: saga step timeout 2 min, sweep every 5 s, reconciler every 10 s with the first look 30 s after a timeout, payment read timeout 2 s, sandbox stall 5 s |

## Result

**5 of 5 runs passed: all nine scenarios in each, 45 of 45, with 0 invariant violations.** A run took 396–486 s.

Times are min / median / max over the five runs, in seconds. "After recovery" counts from the moment the broken dependency was healthy again.

| Scenario | What was done | Expected | Observed |
|---|---|---|---|
| Inventory killed mid-checkout | 20 orders placed, then `docker kill inventory-service`, 20 s down, restarted | All 20 CONFIRMED (NFR-04: within 2 min of recovery) | 20 of 20 CONFIRMED every run. Terminal **19.6 / 19.6 / 19.7** after recovery |
| Lost acknowledgement | Sandbox `CHARGE_THEN_STALL`: the charge is made, but its answer comes after payment-service's 2 s timeout. The attempt goes UNKNOWN | Reconciler finds the charge; CONFIRMED; one charge; reconciliation counter +1 | CONFIRMED, 1 charge, `payment_reconciled_total{SUCCEEDED}` +1, every run. Confirmed **37.6 / 39.6 / 40.7** after the attempt went UNKNOWN (first look is 30 s after the timeout) |
| Payment killed after the charge | Same stall; payment-service killed once the sandbox holds the charge, before its answer is recorded | One charge, CONFIRMED | Attempt was still INITIATED at the kill, every run. The redelivered command re-sent the charge under the same key and got the stored answer: 1 charge, CONFIRMED, **28.4 / 28.5 / 29.6** after restart |
| Every message three times (NFR-02) | Every new record on the five checkout topics copied twice more, byte for byte; 10 `tok_success` and 5 `tok_decline` orders | 10 CONFIRMED with exactly one charge each, 5 FAILED | As expected, every run. 272 extra deliveries per run. Terminal in **5.2 / 8.2 / 9.3** |
| Last unit (NFR-01) | 100 concurrent orders for a product with stock 1 | 1 CONFIRMED, 99 FAILED OUT_OF_STOCK, stock 0 available / 1 committed | Exactly that, every run. All terminal in **1.75 / 1.77 / 1.84** |
| Order database cut | Toxiproxy disables order-service's Postgres link for 30 s while orders keep arriving | 503 during the cut, nothing accepted, no partial order, recovery without a restart | 7 of 7 requests during the cut answered **503**, every run, so none were accepted. No order without lines or saga. Same container start time before and after. The first order after the link returned was accepted **3.0 s** later (3.04–3.07). All orders CONFIRMED, **4.2 / 8.3 / 11.4** after recovery |
| Kafka stopped | `docker stop kafka` for 60 s; one order every 5 s meanwhile | Orders accepted, nothing lost, backlog drains | 12 orders accepted during each outage, all CONFIRMED, **4.2 / 6.2 / 7.2** after Kafka was healthy. Oldest unpublished outbox event reached **50 / 53 / 55 s**, then 0 |
| Replay | Every original command and event of the run so far published again (see below) | No state change; nothing dead-lettered | Snapshot unchanged every run (order and reservation states, charges and refunds, stock sums, attempt counts). 749–759 records replayed, 809–819 consumed (the replays plus the answers they drew), 0 dead-lettered, consumed in **0.62 / 0.66 / 0.82** |
| Slow, unreliable consumers | order, inventory and payment recreated with 5 s added to every record and 30 % of handling attempts failing; 8 `tok_success`, 2 `tok_decline` orders | Invariants hold; the declined ones FAIL | All 10 terminal in **76 / 113 / 162**; 8 of 8 paid orders CONFIRMED and 2 of 2 declined FAILED, every run. 21–36 injected failures per run, all absorbed by the retries |

### About the replay scenario

The roadmap says "replay topics from offset 0". The scenario does not do that, on purpose. It replays every original record that the run itself produced, from the topic offsets recorded when the run started. It skips copies (the three-times scenario's duplicates and earlier replays), and marks its own so they are never replayed again.

An earlier version did replay from offset 0, and each replay then included every earlier replay. The topics doubled from run to run: 95,288 records replayed, then 198,813, 413,070 and 856,086, until a fifth run could not consume them in its 5 minutes. Nothing was applied twice at any of those sizes, but a test that grows every time it runs says nothing reliable about the next run. Older history on these topics was replayed many times over by those runs, and in each one that finished, the state did not change. They are not counted here, because they were not part of this sequence.

## What these numbers do not show

- **Alert firing for the Kafka outage.** `OutboxBacklogStuck` fires when the oldest unpublished event is over 60 s old for 2 minutes. A 60 s outage peaks at about 55 s, so by design it does not fire. The tests record the outbox age itself instead.
- **The deadline sweeper and the reconciler's 15-minute window.** No scenario here loses a message permanently, so recovery came from Kafka redelivery and the reconciler's first look. Re-sends, compensation after the last re-send, `TIMEOUT_UNRESOLVED` and late-charge refunds are covered by integration tests against real Postgres, Kafka and the sandbox (`SagaSweeperIT`, `ReconcilerIT`), not by these runs.
- **Load.** The scenarios place 1 to 100 orders at a time. Behaviour under sustained load is Milestone 12.
- **Hardware.** One laptop runs everything, including the test driver. Times would differ on separate machines, and a 5 s injected delay dominates the chaos scenario's times.

## Problems these runs found (fixed before the five counted runs)

- With the database link cut, the first request answered 500, not 503. The statement failed on the dead connection, then so did the rollback, and the rollback's exception hid the first. HTTP and the consumers' outage check now both treat it as the database being unreachable.
- The gateway kept sending a recreated container's traffic to its old address for up to 10 minutes. Netty caches DNS for Docker's 600 s TTL. It is now capped at 5 s.
- `dev-up` could ship a service with stale copies of libraries that had changed. The build now forces the jars to be re-created.
- The replay check had only waited for consumer lag to reach zero. It now also waits until the consumers have handled as many records as were replayed.
