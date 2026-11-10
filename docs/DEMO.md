# Demo: ten minutes

What to show, in order, and what each step proves. It starts from a running stack (`scripts/dev-up.sh --obs --apps`, see [SETUP.md](SETUP.md)).

## 1. A checkout, followed live (2 min)

1. Open http://localhost:8000, register a customer, add two products to the cart, and check out with the test card **"Card that is charged"**.
2. The order page follows the saga step by step: PENDING, then PAYMENT_PROCESSING, then CONFIRMED. Point out what is happening behind it. order-service wrote the order and its first command in one transaction (the outbox). Inventory reserved the stock. The payment sandbox charged the card once. Then the stock was committed.
3. The checkout carries an Idempotency-Key that belongs to the attempt, not to the click. If an answer is lost, the page says "Try again: it will not be placed twice". The retry sends the same key, and the same key returns the same order. To show it: stop the gateway (`docker stop commerceflow-api-gateway-1`) just after pressing "Place order", start it again, and press "Place order" once more. You get one order. The web app's tests cover this, as does the Kubernetes test, where retries during a hung pod made no second order.

## 2. A declined card, compensated (1 min)

Check out again with **"Card that is declined"**. The order ends FAILED with `PAYMENT_DECLINED`, and the product's stock is back where it was: the reservation was released. That is a compensating step, not a rollback.

## 3. One trace through everything (2 min)

In Grafana (http://localhost:3000) open **Explore → Tempo**, and search for the last few traces of the gateway's `POST /api/v1/orders`. One trace spans the gateway, order-service, Kafka, inventory-service and payment-service. The outbox stores each event's trace context, so the consumer's spans join the writer's trace. In the logs (Loki), the same `traceId` and `correlationId` are on every line, and no email or token appears in them.

Then the **Checkout** and **Service latency** dashboards: rates and p95/p99 per route, never averages.

## 4. A service killed mid-checkout (3 min)

```bash
docker kill commerceflow-inventory-service-1
```

Place two orders in the web app: they are accepted and wait (PENDING). After about 20 s:

```bash
docker start commerceflow-inventory-service-1
```

Both orders reach CONFIRMED on their own. Inventory's commands waited in Kafka, and its consumer took them up from where it stopped. In the failure-recovery runs this took about 20 s after recovery ([report](reports/failure-recovery.md)).

## 5. The admin side (1 min)

Sign in as the admin (`BOOTSTRAP_ADMIN_EMAIL` in `.env`): orders by status, stock with restock and write-off, sagas past their deadline, payment attempts with "Reconcile now", and dead letters with replay. A customer's account sees none of these pages, and the API answers 403 if asked.

## 6. If there is time

- `scripts/kind-up.sh`, then `scripts/kind-chaos.sh`: the same system on Kubernetes, with checkouts continuing while a pod hangs and another is deleted ([ADR-0015](adr/0015-kubernetes-on-kind.md)).
- [The performance report](reports/performance.md): how the gateway's tail latency was traced to a garbage collector nobody had chosen.
