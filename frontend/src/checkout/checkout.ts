/**
 * Checkout as a small state machine, kept apart from the screen so it can be tested on its own.
 *
 * The Idempotency-Key belongs to the checkout attempt, not to the click (ROADMAP M8 gate). It is
 * made when an attempt starts and reused for every retry of that attempt (a double click, a lost
 * response, a 5xx, a 429), so order-service returns the same order instead of creating a second
 * one. It is dropped only on an answer that ends the attempt: the order was accepted, or the
 * request itself was refused (4xx), after which the customer changes something and starts anew.
 */

export type PaymentToken = "tok_success" | "tok_decline" | "tok_insufficient" | "tok_timeout" | "tok_unknown";

/** The sandbox's test tokens, with what each one does (no real card data ever exists here). */
export const PAYMENT_TOKENS: { token: PaymentToken; label: string }[] = [
  { token: "tok_success", label: "Card that is charged" },
  { token: "tok_decline", label: "Card that is declined" },
  { token: "tok_insufficient", label: "Card with insufficient funds" },
  { token: "tok_timeout", label: "Card whose charge answers too late (reconciled)" },
  { token: "tok_unknown", label: "Provider that fails without charging" },
];

export type CheckoutState =
  | { kind: "ready"; key: string | null; error?: string }
  | { kind: "submitting"; key: string }
  | { kind: "placed"; orderId: string };

export type CheckoutEvent =
  | { type: "submit"; newKey: () => string }
  | { type: "accepted"; orderId: string }
  | { type: "refused"; message: string }
  | { type: "retryable"; message: string };

export const initialCheckout: CheckoutState = { kind: "ready", key: null };

export function checkoutReducer(state: CheckoutState, event: CheckoutEvent): CheckoutState {
  switch (event.type) {
    case "submit":
      if (state.kind !== "ready") {
        // Already in flight or done: a second click does nothing.
        return state;
      }
      return { kind: "submitting", key: state.key ?? event.newKey() };
    case "accepted":
      return { kind: "placed", orderId: event.orderId };
    case "refused":
      // The request was wrong as sent; the next attempt is a different request, with a new key.
      return { kind: "ready", key: null, error: event.message };
    case "retryable":
      // The outcome is unknown or temporary: the same attempt, and the same key, next time.
      return state.kind === "submitting"
        ? { kind: "ready", key: state.key, error: event.message }
        : state;
  }
}

/** Which answers end an attempt and which leave it open for a retry with the same key. */
export function classify(status: number): "accepted" | "refused" | "retryable" {
  if (status === 200 || status === 202) {
    return "accepted";
  }
  if (status === 429 || status >= 500 || status <= 0) {
    return "retryable";
  }
  return "refused";
}

export function newIdempotencyKey(): string {
  return `web-${crypto.randomUUID()}`;
}
