import { useEffect, useState } from "react";
import { describe, orders } from "../../api/client";
import type { components } from "../../api/schema/order-service";
import { OrderTable } from "../OrdersPage";

type Status = components["schemas"]["OrderStatus"];
const STATUSES: Status[] = ["PENDING", "PAYMENT_PROCESSING", "CONFIRMED", "FAILED"];

/** Every customer's orders, by status: what FR-15's dashboard starts from. */
export function AdminOrdersPage() {
  const [status, setStatus] = useState<Status | "">("");
  const [items, setItems] = useState<components["schemas"]["Order"][]>([]);
  const [counts, setCounts] = useState<Partial<Record<Status, number>>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void orders
      .GET("/api/v1/admin/orders", {
        params: { query: { status: status || undefined, size: 50 } },
      })
      .then(({ data, error }) => {
        setItems(data?.items ?? []);
        setError(data ? null : describe(error, "Orders could not be loaded."));
      });
  }, [status]);

  useEffect(() => {
    void Promise.all(
      STATUSES.map(async (s) => {
        const { data } = await orders.GET("/api/v1/admin/orders", {
          params: { query: { status: s, size: 1 } },
        });
        return [s, data?.totalItems ?? 0] as const;
      }),
    ).then((pairs) => setCounts(Object.fromEntries(pairs)));
  }, []);

  return (
    <>
      <h1>Orders</h1>
      <ul className="grid" aria-label="Orders by status">
        {STATUSES.map((s) => (
          <li key={s} className="card">
            <span className="muted">{s}</span>
            <strong>{counts[s] ?? "—"}</strong>
          </li>
        ))}
      </ul>
      <label>
        Status
        <select value={status} onChange={(event) => setStatus(event.target.value as Status | "")}>
          <option value="">All</option>
          {STATUSES.map((s) => (
            <option key={s}>{s}</option>
          ))}
        </select>
      </label>
      {error && <p role="alert">{error}</p>}
      <div className="table-wrap">
        <OrderTable items={items} />
      </div>
    </>
  );
}
