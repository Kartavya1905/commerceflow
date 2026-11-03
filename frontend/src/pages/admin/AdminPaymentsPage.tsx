import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { describe, payments } from "../../api/client";
import type { components } from "../../api/schema/payment-service";
import { money, when } from "../../lib/format";

type Attempt = components["schemas"]["PaymentAttempt"];
type Status = components["schemas"]["PaymentStatus"];
const STATUSES: Status[] = [
  "INITIATED",
  "SUCCEEDED",
  "FAILED",
  "UNKNOWN",
  "CANCELLED",
  "REFUND_PENDING",
  "REFUNDED",
];

/** Payment attempts: at most one per order, its id the provider's idempotency key. */
export function AdminPaymentsPage() {
  const [filter, setFilter] = useState<{ orderId?: string; status?: Status }>({ status: "UNKNOWN" });
  const [items, setItems] = useState<Attempt[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void payments
      .GET("/api/v1/admin/payments", { params: { query: filter } })
      .then(({ data, error }) => {
        setItems(data?.items ?? []);
        setError(data ? null : describe(error, "Payments could not be loaded."));
      });
  }, [filter]);

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const orderId = String(form.get("orderId") ?? "").trim();
    const status = String(form.get("status") ?? "") as Status | "";
    setFilter({ orderId: orderId || undefined, status: status || undefined });
  }

  return (
    <>
      <h1>Payments</h1>
      <form className="row" onSubmit={search} aria-label="Find payments">
        <label>
          Order id
          <input name="orderId" pattern="[0-9a-fA-F-]{36}" title="An order id" />
        </label>
        <label>
          Status
          <select name="status" defaultValue="UNKNOWN">
            <option value="">Any</option>
            {STATUSES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </label>
        <button type="submit">Find</button>
      </form>
      {error && <p role="alert">{error}</p>}
      {items.length === 0 && !error && <p>No payment attempts match.</p>}
      {items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Order</th>
                <th>Amount</th>
                <th>Status</th>
                <th>Looks</th>
                <th>Next look</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {items.map((a) => (
                <tr key={a.id}>
                  <td>
                    <code>{a.orderId}</code>
                  </td>
                  <td>{a.amountMinor != null && a.currency ? money(a.amountMinor, a.currency) : "—"}</td>
                  <td>
                    {a.status}
                    {a.failureCode && <span className="muted"> · {a.failureCode}</span>}
                  </td>
                  <td>{a.reconcileAttempts ?? 0}</td>
                  <td>{when(a.nextReconcileAt)}</td>
                  <td>{when(a.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
