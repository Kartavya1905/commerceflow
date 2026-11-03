import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import { fakeApi } from "../test/fakeApi";
import type { Call } from "../test/fakeApi";
import { CheckoutPage } from "./CheckoutPage";

const PRODUCT = "11111111-1111-4111-8111-111111111111";
const ORDER = "22222222-2222-4222-8222-222222222222";

function api(orderAnswers: number[]) {
  const answers = [...orderAnswers];
  return fakeApi({
    "GET /api/v1/cart": () => ({ status: 200, body: { items: [{ productId: PRODUCT, quantity: 2 }] } }),
    [`GET /api/v1/products/${PRODUCT}`]: () => ({
      status: 200,
      body: { id: PRODUCT, name: "Lamp", priceMinor: 1999, currency: "USD", sku: "L-1", category: "x", status: "ACTIVE", version: 0 },
    }),
    "POST /api/v1/orders": () => {
      const status = answers.shift() ?? 500;
      return status === 202
        ? { status, body: { id: ORDER, status: "PENDING", items: [], totalMinor: 3998, currency: "USD", createdAt: "2026-11-02T00:00:00Z" } }
        : { status, body: { title: "Nope", status, detail: status === 422 ? "Refused." : "Unavailable." } };
    },
    "DELETE /api/v1/cart": () => ({ status: 204 }),
  });
}

function renderCheckout() {
  render(
    <MemoryRouter initialEntries={["/checkout"]}>
      <Routes>
        <Route path="/checkout" element={<CheckoutPage />} />
        <Route path="/orders/:orderId" element={<p>order page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

const posts = (calls: Call[]) => calls.filter((c) => c.method === "POST" && c.path === "/api/v1/orders");
const keyOf = (call: Call) => call.headers.get("Idempotency-Key");

describe("checkout", () => {
  it("sends one order however many times the button is clicked", async () => {
    const calls = api([202]);
    renderCheckout();
    const button = await screen.findByRole("button", { name: "Place order" });
    const user = userEvent.setup();
    await user.click(button);
    await user.click(button);
    await user.click(button);
    await screen.findByText("order page");
    expect(posts(calls)).toHaveLength(1);
    expect(posts(calls)[0]!.body).toEqual({
      items: [{ productId: PRODUCT, quantity: 2 }],
      paymentToken: "tok_success",
    });
  });

  it("retries a failed attempt with the same Idempotency-Key, so it cannot order twice", async () => {
    const calls = api([503, 202]);
    renderCheckout();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Place order" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("it will not be placed twice");
    await user.click(screen.getByRole("button", { name: "Place order" }));
    await screen.findByText("order page");
    const sent = posts(calls);
    expect(sent).toHaveLength(2);
    expect(keyOf(sent[0]!)).toBeTruthy();
    expect(keyOf(sent[1]!)).toBe(keyOf(sent[0]!));
    // The cart is emptied only once the order exists.
    expect(calls.some((c) => c.method === "DELETE" && c.path === "/api/v1/cart")).toBe(true);
  });

  it("starts a new attempt, with a new key, after the order was refused", async () => {
    const calls = api([422, 202]);
    renderCheckout();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Place order" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Refused.");
    await user.click(screen.getByRole("radio", { name: /declined/ }));
    await user.click(screen.getByRole("button", { name: "Place order" }));
    await screen.findByText("order page");
    const sent = posts(calls);
    expect(keyOf(sent[1]!)).not.toBe(keyOf(sent[0]!));
    expect(sent[1]!.body).toMatchObject({ paymentToken: "tok_decline" });
  });

  it("keeps the cart when the order was not placed", async () => {
    const calls = api([422]);
    renderCheckout();
    await userEvent.setup().click(await screen.findByRole("button", { name: "Place order" }));
    await screen.findByRole("alert");
    await waitFor(() => expect(posts(calls)).toHaveLength(1));
    expect(calls.some((c) => c.method === "DELETE")).toBe(false);
  });
});
