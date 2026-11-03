import { useCallback, useEffect, useState } from "react";
import { describe, orders } from "../../api/client";
import type { components } from "../../api/schema/order-service";
import { when } from "../../lib/format";

type DeadLetter = components["schemas"]["DeadLetter"];

/** The checkout's dead-letter topics, from contracts/.../events/topics.json. */
export const DLT_TOPICS = [
  "order.events.v1.dlt",
  "inventory.commands.v1.dlt",
  "inventory.events.v1.dlt",
  "payment.commands.v1.dlt",
  "payment.events.v1.dlt",
];

/**
 * Parked messages and replay. A replay puts the record back on its original topic unchanged, so
 * its consumer's dedupe applies: replaying twice is harmless. Fix the cause first (runbook,
 * "Dead-letter messages"), or the record is parked again.
 */
export function AdminDeadLettersPage() {
  const [topic, setTopic] = useState(DLT_TOPICS[0]!);
  const [items, setItems] = useState<DeadLetter[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    const { data, error, response } = await orders.GET("/api/v1/admin/dlt/{topic}", {
      params: { path: { topic }, query: { limit: 200 } },
    });
    // A dead-letter topic that has never received anything may not exist yet: that is empty.
    setItems(data?.items ?? []);
    if (!data && response.status !== 404) {
      setMessage(describe(error, "Dead letters could not be loaded."));
    }
  }, [topic]);

  useEffect(() => {
    setItems(null);
    setMessage(null);
    void load();
  }, [load]);

  async function replay(entry: DeadLetter) {
    if (!window.confirm(`Replay ${entry.eventType ?? "this record"} to ${entry.originalTopic}?`)) {
      return;
    }
    const { error, response } = await orders.POST("/api/v1/admin/dlt/{topic}/replay", {
      params: { path: { topic } },
      body: { partition: entry.partition, offset: entry.offset },
    });
    setMessage(
      response.status === 202
        ? `Replayed to ${entry.originalTopic}. It stays listed here: a dead-letter topic keeps its history.`
        : describe(error, "The replay failed."),
    );
  }

  return (
    <>
      <h1>Dead letters</h1>
      <label>
        Topic
        <select value={topic} onChange={(event) => setTopic(event.target.value)}>
          {DLT_TOPICS.map((t) => (
            <option key={t}>{t}</option>
          ))}
        </select>
      </label>
      {message && <p role="status">{message}</p>}
      {items === null && <p aria-busy="true">Loading…</p>}
      {items?.length === 0 && <p>Nothing parked in {topic}.</p>}
      {items && items.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Parked</th>
                <th>Event</th>
                <th>Key</th>
                <th>Why</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {items.map((entry) => (
                <tr key={`${entry.partition}-${entry.offset}`}>
                  <td>{when(entry.parkedAt)}</td>
                  <td>
                    {entry.eventType ?? "unreadable"}
                    <br />
                    <span className="muted">
                      {entry.partition}@{entry.offset}
                    </span>
                  </td>
                  <td>
                    <code>{entry.key}</code>
                  </td>
                  <td>
                    <code>{entry.failure}</code>
                  </td>
                  <td>
                    <button type="button" onClick={() => void replay(entry)}>
                      Replay
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
