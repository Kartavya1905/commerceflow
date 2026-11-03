import { describe, expect, it } from "vitest";
import { checkoutReducer, classify, initialCheckout } from "./checkout";

let n = 0;
const keys = () => `key-${++n}`;

describe("checkout state", () => {
  it("makes one key per attempt and ignores a second submit while in flight", () => {
    const submitting = checkoutReducer(initialCheckout, { type: "submit", newKey: keys });
    expect(submitting).toMatchObject({ kind: "submitting" });
    expect(checkoutReducer(submitting, { type: "submit", newKey: keys })).toBe(submitting);
  });

  it("keeps the key across a retryable failure, so the retry cannot create a second order", () => {
    const first = checkoutReducer(initialCheckout, { type: "submit", newKey: () => "attempt-1" });
    const failed = checkoutReducer(first, { type: "retryable", message: "timeout" });
    expect(failed).toEqual({ kind: "ready", key: "attempt-1", error: "timeout" });
    const retry = checkoutReducer(failed, { type: "submit", newKey: () => "never-used" });
    expect(retry).toEqual({ kind: "submitting", key: "attempt-1" });
  });

  it("drops the key when the request is refused, so a changed request gets a new one", () => {
    const first = checkoutReducer(initialCheckout, { type: "submit", newKey: () => "attempt-1" });
    const refused = checkoutReducer(first, { type: "refused", message: "unavailable" });
    expect(refused).toEqual({ kind: "ready", key: null, error: "unavailable" });
    const next = checkoutReducer(refused, { type: "submit", newKey: () => "attempt-2" });
    expect(next).toEqual({ kind: "submitting", key: "attempt-2" });
  });

  it("ends with the order once it is accepted, and stays there", () => {
    const first = checkoutReducer(initialCheckout, { type: "submit", newKey: keys });
    const placed = checkoutReducer(first, { type: "accepted", orderId: "o-1" });
    expect(placed).toEqual({ kind: "placed", orderId: "o-1" });
    expect(checkoutReducer(placed, { type: "submit", newKey: keys })).toBe(placed);
  });

  it.each([
    [202, "accepted"],
    [200, "accepted"],
    [400, "refused"],
    [409, "refused"],
    [422, "refused"],
    [429, "retryable"],
    [500, "retryable"],
    [503, "retryable"],
    [0, "retryable"],
  ])("classifies %i as %s", (status, kind) => {
    expect(classify(status)).toBe(kind);
  });
});
