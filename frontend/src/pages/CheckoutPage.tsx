import { useEffect, useReducer, useState } from "react";
import { Link, useNavigate } from "react-router";
import { describe, orders } from "../api/client";
import { total, useCart } from "../cart/cart";
import {
  PAYMENT_TOKENS,
  checkoutReducer,
  classify,
  initialCheckout,
  newIdempotencyKey,
} from "../checkout/checkout";
import type { PaymentToken } from "../checkout/checkout";
import { money } from "../lib/format";

export function CheckoutPage() {
  const { lines, clear } = useCart();
  const navigate = useNavigate();
  const [state, dispatch] = useReducer(checkoutReducer, initialCheckout);
  const [token, setToken] = useState<PaymentToken>("tok_success");

  // Sending is driven by the state, not the click: only a fresh "submitting" sends, with the
  // attempt's key, so a double click or a re-render can never send a second, different request.
  useEffect(() => {
    if (state.kind !== "submitting" || !lines) {
      return;
    }
    let current = true;
    void (async () => {
      const { data, error, response } = await orders
        .POST("/api/v1/orders", {
          params: { header: { "Idempotency-Key": state.key } },
          body: {
            items: lines.map(({ productId, quantity }) => ({ productId, quantity })),
            paymentToken: token,
          },
        })
        .catch(() => ({ data: undefined, error: undefined, response: { status: 0 } as Response }));
      if (!current) {
        return;
      }
      switch (classify(response.status)) {
        case "accepted":
          dispatch({ type: "accepted", orderId: data!.id });
          break;
        case "refused":
          dispatch({ type: "refused", message: describe(error, "The order was not accepted.") });
          break;
        case "retryable":
          dispatch({
            type: "retryable",
            message:
              "We could not confirm the order was placed. Try again: it will not be placed twice.",
          });
          break;
      }
    })();
    return () => {
      current = false;
    };
    // The key identifies the attempt; lines and token are read as they were when it started.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.kind === "submitting" ? state.key : null]);

  useEffect(() => {
    if (state.kind === "placed") {
      void clear().then(() => navigate(`/orders/${state.orderId}`, { replace: true }));
    }
  }, [state, clear, navigate]);

  if (!lines) {
    return <p aria-busy="true">Loading…</p>;
  }
  if (lines.length === 0 && state.kind === "ready") {
    return (
      <p>
        Your cart is empty. <Link to="/">Browse products</Link>
      </p>
    );
  }
  const sum = total(lines);
  const submitting = state.kind !== "ready";
  return (
    <form
      className="card narrow"
      onSubmit={(event) => {
        event.preventDefault();
        dispatch({ type: "submit", newKey: newIdempotencyKey });
      }}
    >
      <h1>Checkout</h1>
      <p>
        {lines.length} {lines.length === 1 ? "product" : "products"}, estimated{" "}
        <strong>{sum.currency ? money(sum.minor, sum.currency) : "—"}</strong>
      </p>
      <fieldset disabled={submitting}>
        <legend>Payment (sandbox test cards)</legend>
        {PAYMENT_TOKENS.map((option) => (
          <label key={option.token} className="choice">
            <input
              type="radio"
              name="paymentToken"
              value={option.token}
              checked={token === option.token}
              onChange={() => setToken(option.token)}
            />
            {option.label}
          </label>
        ))}
      </fieldset>
      {state.kind === "ready" && state.error && <p role="alert">{state.error}</p>}
      <button type="submit" disabled={submitting}>
        {submitting ? "Placing order…" : "Place order"}
      </button>
    </form>
  );
}
