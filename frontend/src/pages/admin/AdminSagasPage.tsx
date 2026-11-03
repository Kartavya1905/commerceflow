import { useEffect, useState } from "react";
import { describe, orders } from "../../api/client";
import type { components } from "../../api/schema/order-service";
import { when } from "../../lib/format";

type Saga = components["schemas"]["Saga"];

/**
 * The checkout sagas. "Stuck" means past its deadline: normally the sweeper is already re-sending
 * or compensating it (docs/runbooks/infra.md, "An order is stuck in checkout").
 */
export function AdminSagasPage() {
  const [stuck, setStuck] = useState(true);
  const [items, setItems] = useState<Saga[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void orders
      .GET("/api/v1/admin/sagas", { params: { query: { stuck, size: 100 } } })
      .then(({ data, error }) => {
        setItems(data?.items ?? []);
        setError(data ? null : describe(error, "Sagas could not be loaded."));
      });
  }, [stuck]);

  return (
    <>
      <h1>Sagas</h1>
      <label className="choice">
        <input type="checkbox" checked={stuck} onChange={(event) => setStuck(event.target.checked)} />
        Only those past their deadline
      </label>
      {error && <p role="alert">{error}</p>}
      {items.length === 0 && !error && <p>{stuck ? "No saga is past its deadline." : "No sagas."}</p>}
      {items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Order</th>
                <th>State</th>
                <th>Re-sends</th>
                <th>Deadline</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {items.map((saga) => (
                <tr key={saga.id}>
                  <td>
                    <code>{saga.orderId}</code>
                  </td>
                  <td>{saga.state}</td>
                  <td>{saga.stepAttempts}</td>
                  <td>{when(saga.deadlineAt)}</td>
                  <td>{when(saga.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
