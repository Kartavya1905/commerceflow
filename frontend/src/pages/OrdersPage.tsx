import { useEffect, useState } from "react";
import { Link } from "react-router";
import { describe, orders } from "../api/client";
import type { components } from "../api/schema/order-service";
import { money, statusLabel, when } from "../lib/format";

type OrderPage = components["schemas"]["OrderPage"];

export function OrdersPage() {
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<OrderPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void orders
      .GET("/api/v1/orders", { params: { query: { page, size: 20 } } })
      .then(({ data, error }) => {
        setResult(data ?? null);
        setError(data ? null : describe(error, "Your orders could not be loaded."));
      });
  }, [page]);

  return (
    <>
      <h1>Your orders</h1>
      {error && <p role="alert">{error}</p>}
      {result && result.items.length === 0 && <p>No orders yet.</p>}
      {result && result.items.length > 0 && (
        <OrderTable items={result.items} link={(id) => `/orders/${id}`} />
      )}
      {result && result.totalItems > 20 && (
        <div className="row">
          <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </button>
          <button
            type="button"
            disabled={(page + 1) * 20 >= result.totalItems}
            onClick={() => setPage(page + 1)}
          >
            Next
          </button>
        </div>
      )}
    </>
  );
}

export function OrderTable({
  items,
  link,
}: {
  items: components["schemas"]["Order"][];
  link?: (id: string) => string;
}) {
  return (
    <table>
      <thead>
        <tr>
          <th>Placed</th>
          <th>Order</th>
          <th>Total</th>
          <th>Status</th>
        </tr>
      </thead>
      <tbody>
        {items.map((order) => (
          <tr key={order.id}>
            <td>{when(order.createdAt)}</td>
            <td>
              {link ? <Link to={link(order.id)}>{order.id.slice(0, 8)}</Link> : order.id}
            </td>
            <td>{money(order.totalMinor, order.currency)}</td>
            <td>
              <span className={`status ${order.status.toLowerCase()}`}>{statusLabel(order.status)}</span>
              {order.failureReason && <span className="muted"> · {order.failureReason}</span>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
