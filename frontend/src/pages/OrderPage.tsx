import { useEffect, useState } from "react";
import { useParams } from "react-router";
import { orders } from "../api/client";
import type { components } from "../api/schema/order-service";
import { TERMINAL, failureLabel, money, statusLabel, when } from "../lib/format";
import { pollWithBackoff } from "../lib/poll";

type Order = components["schemas"]["Order"];

/**
 * One order, with its status kept live until it is final. The checkout runs asynchronously, so a
 * new order starts as "Reserving stock" and moves on by itself; polling backs off (1 s, 2 s,
 * 4 s... up to 15 s) and stops once the order is confirmed or failed, or the page is left.
 */
export function OrderPage() {
  const { orderId = "" } = useParams();
  const [order, setOrder] = useState<Order | null>(null);
  const [missing, setMissing] = useState(false);

  useEffect(
    () =>
      pollWithBackoff(
        async () => {
          const { data, response } = await orders.GET("/api/v1/orders/{orderId}", {
            params: { path: { orderId } },
          });
          if (response.status === 404) {
            setMissing(true);
          }
          return data ?? null;
        },
        (value) => value === null || TERMINAL.has(value.status),
        (value) => value && setOrder(value),
      ),
    [orderId],
  );

  if (missing) {
    return <p role="alert">There is no such order.</p>;
  }
  if (!order) {
    return <p aria-busy="true">Loading…</p>;
  }
  const live = !TERMINAL.has(order.status);
  return (
    <article className="card">
      <h1>Order {order.id.slice(0, 8)}</h1>
      <p aria-live="polite">
        <span className={`status ${order.status.toLowerCase()}`} data-testid="order-status">
          {statusLabel(order.status)}
        </span>
        {live && <span className="muted"> · updating</span>}
      </p>
      {order.status === "FAILED" && <p>{failureLabel(order.failureReason)}</p>}
      <table>
        <thead>
          <tr>
            <th>Product</th>
            <th>Quantity</th>
            <th>Price</th>
          </tr>
        </thead>
        <tbody>
          {order.items.map((item) => (
            <tr key={item.productId}>
              <td>{item.name}</td>
              <td>{item.quantity}</td>
              <td>{money(item.unitPriceMinor * item.quantity, order.currency)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>
        Total <strong>{money(order.totalMinor, order.currency)}</strong>
      </p>
      <p className="muted">Placed {when(order.createdAt)}</p>
    </article>
  );
}
