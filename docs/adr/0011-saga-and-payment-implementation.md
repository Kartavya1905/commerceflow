# ADR-0011: How the checkout saga and payments are implemented

- Status: Accepted
- Date: 2026-11-02
- Refines: ADR-0003 (orchestrated saga), ADR-0008 (self-hosted payment sandbox)

## Context
ADR-0003 chose an orchestrated saga in order-service, and ADR-0008 a self-hosted card provider. Building them in Milestone 6 required narrower decisions about money and state, each of which a reviewer should be able to check against the code.

## Decisions

### Saga (order-service)
1. **The saga is data.** `SagaTransitions.TABLE` is a `Map<(state, event), Transition>` holding the target state, the order status change, the command to send and the failure reason. `SagaOrchestrator` has no business `if`s of its own. `SagaTransitionsTest` checks every one of the 108 (state, event) pairs against a copy of ARCHITECTURE.md E.2 written out separately, and walks the graph to prove that no path ends FAILED with money taken, none ends COMPLETED without it, and every path to FAILED has decided its reason.
2. **Reasons are split into events where the saga's answer depends on them.** `PaymentFailed` becomes `PAYMENT_DECLINED`, `PAYMENT_TIMED_OUT` or `PAYMENT_CANCELLED`, so the table shows the difference instead of a handler hiding it.
3. **One transaction per transition**: the consumer's own (inbox dedupe), with the saga row `FOR UPDATE`, the order status, the next command and any `OrderConfirmed`/`OrderFailed` in it. If the order is not in the status the saga expects, it throws and rolls back the transition rather than let the two disagree.
4. **Invalid-for-state events are acknowledged**, counted in `saga_event_ignored_total{reason,event}`, and never thrown. A test sends three of them and checks that nothing reached a dead-letter topic.
5. **The customer sees FAILED only once stock is back.** While compensating, the order stays `PAYMENT_PROCESSING`. A FAILED order therefore never holds stock.
6. **Deadlines are recorded now; the sweeper that acts on them is Milestone 7.** `deadline_at` is set on every waiting state, and the table already has the `DEADLINE_EXCEEDED` transitions.
7. **An order must total more than zero.** Catalog allows free products, but `AuthorizePayment` and `OrderConfirmed` need an amount and a payment attempt. Such orders are refused with 422 `zero-total` at placement.

### Payments (payment-service)
8. **One attempt per order** (`UNIQUE(order_id)`). The attempt id is created once and is the provider's `Idempotency-Key`, so no retry, redelivery or duplicate command can charge twice (INV-3).
9. **Write-ahead, then call, then record:** TX1 commits an `INITIATED` attempt, the provider is called with no transaction open, and TX2 records the outcome with a guarded `UPDATE … WHERE status IN ('INITIATED','UNKNOWN')`. `ProviderClient` refuses to run inside a transaction, so this cannot regress silently.
10. **No event-id dedupe for payment commands.** The provider call cannot sit inside the inbox transaction, so `InboundEvents.handleUntransacted` sets correlation and causation without one. Idempotency comes from the attempt row instead. That also means a re-sent command is answered again with the recorded outcome, which is what a saga that missed the answer needs.
11. **A redelivered command resumes an unsettled attempt** by calling the provider again under the same key. A crash between TX1 and TX2 therefore heals on redelivery, without waiting for the reconciler.
12. **Timeout or error means `UNKNOWN`, never `FAILED`.** `PaymentPendingReconciliation` is published, and the saga waits in `AWAITING_RECONCILIATION` without releasing stock.
13. **CancelPayment is void-or-refund.** With no attempt, it leaves a `CANCELLED` tombstone, so a late `AuthorizePayment` is answered `PaymentFailed(CANCELLED)` and charges nothing. With money taken, it refunds once (key `refund-<attemptId>`). With the outcome unknown, it asks the provider by idempotency key and then refunds or cancels.
14. **A charge that lands after its attempt was cancelled is refunded automatically.** If TX2 finds the attempt `CANCELLED` and the provider says it succeeded, the attempt moves to `REFUND_PENDING` and is refunded.
15. **The sandbox is a separate process in tests too.** payment-service's tests start its built jar with its own Postgres, so "the provider kept the charge" is checked in the provider's own records, as it would be in production.

## Consequences
- An `UNKNOWN` attempt, and its order, wait until the Milestone 7 reconciler resolves it. Until then, a `tok_timeout` order stays `PAYMENT_PROCESSING` with its stock held. That is the safe direction.
- If payment-service dies after cancelling an in-flight attempt but before that charge's answer is recorded, the charge exists at the provider with no one to refund it. The Milestone 7 reconciler must also look at recently cancelled attempts that were in flight.
- Orders placed before this milestone get saga rows from the migration. Any that reached `PAYMENT_PROCESSING` never had payment requested, so their deadline is already due for the Milestone 7 sweeper.
