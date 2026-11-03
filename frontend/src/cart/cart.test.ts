import { describe, expect, it } from "vitest";
import { total } from "./cart";

const line = (priceMinor: number | null, quantity: number, currency: string | null = "USD") => ({
  productId: crypto.randomUUID(),
  name: "x",
  quantity,
  priceMinor,
  currency,
});

describe("cart total", () => {
  it("adds price times quantity", () => {
    expect(total([line(1999, 2), line(500, 1)])).toEqual({
      minor: 4498,
      currency: "USD",
      complete: true,
    });
  });

  it("is incomplete when a product has no price (archived) or currencies differ", () => {
    expect(total([line(1000, 1), line(null, 1, null)]).complete).toBe(false);
    expect(total([line(1000, 1), line(1000, 1, "EUR")])).toMatchObject({
      currency: null,
      complete: false,
    });
  });

  it("is zero and complete for an empty cart", () => {
    expect(total([])).toEqual({ minor: 0, currency: null, complete: true });
  });
});
