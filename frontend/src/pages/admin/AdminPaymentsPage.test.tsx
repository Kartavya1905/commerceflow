import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeApi } from "../../test/fakeApi";
import { AdminPaymentsPage } from "./AdminPaymentsPage";

const attempt = (id: string, status: string, nextReconcileAt?: string) => ({
  id,
  orderId: `${id}-order`,
  amountMinor: 1000,
  currency: "USD",
  status,
  createdAt: "2026-11-03T00:00:00Z",
  ...(nextReconcileAt ? { nextReconcileAt } : {}),
});

describe("payments admin page", () => {
  it("offers reconcile now only where the reconciler has something to look for, and asks for it", async () => {
    const calls = fakeApi({
      "GET /api/v1/admin/payments": () => ({
        status: 200,
        body: {
          items: [
            attempt("unknown", "UNKNOWN", "2026-11-03T00:01:00Z"),
            attempt("settled", "SUCCEEDED"),
            attempt("closed-done", "CANCELLED"),
            attempt("closed-due", "CANCELLED", "2026-11-03T00:01:00Z"),
          ],
        },
      }),
      "POST /api/v1/admin/payments/unknown/reconcile": () => ({ status: 202 }),
    });
    render(<AdminPaymentsPage />);

    const row = (id: string) => screen.getByText(`${id}-order`).closest("tr")!;
    await screen.findByText("unknown-order");
    expect(within(row("settled")).queryByRole("button")).toBeNull();
    expect(within(row("closed-done")).queryByRole("button")).toBeNull();
    expect(within(row("closed-due")).getByRole("button", { name: "Reconcile now" })).toBeVisible();

    await userEvent.setup().click(within(row("unknown")).getByRole("button", { name: "Reconcile now" }));

    expect(await screen.findByRole("status")).toHaveTextContent("within about 10 seconds");
    expect(calls.filter((c) => c.method === "POST").map((c) => c.path)).toEqual([
      "/api/v1/admin/payments/unknown/reconcile",
    ]);
  });

  it("says why when the server refuses (the attempt settled meanwhile)", async () => {
    fakeApi({
      "GET /api/v1/admin/payments": () => ({
        status: 200,
        body: { items: [attempt("raced", "UNKNOWN")] },
      }),
      "POST /api/v1/admin/payments/raced/reconcile": () => ({
        status: 409,
        body: { title: "Conflict", status: 409, detail: "The attempt is SUCCEEDED: there is nothing left to reconcile." },
      }),
    });
    render(<AdminPaymentsPage />);
    await userEvent.setup().click(await screen.findByRole("button", { name: "Reconcile now" }));
    expect(await screen.findByRole("status")).toHaveTextContent("nothing left to reconcile");
  });
});
