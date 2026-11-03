import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { delays, pollWithBackoff } from "./poll";

describe("polling", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("waits twice as long each time, up to the cap", () => {
    expect(delays(6, 1000, 15_000)).toEqual([1000, 2000, 4000, 8000, 15_000, 15_000]);
  });

  it("backs off between polls and stops when the value is final", async () => {
    const statuses = ["PENDING", "PAYMENT_PROCESSING", "PAYMENT_PROCESSING", "CONFIRMED", "X"];
    const at: number[] = [];
    const seen: string[] = [];
    pollWithBackoff(
      async () => {
        at.push(Date.now());
        return statuses.shift()!;
      },
      (s) => s === "CONFIRMED",
      (s) => seen.push(s),
      { initialMs: 1000, maxMs: 15_000, random: () => 1 },
    );
    await vi.advanceTimersByTimeAsync(60_000);
    expect(seen).toEqual(["PENDING", "PAYMENT_PROCESSING", "PAYMENT_PROCESSING", "CONFIRMED"]);
    const gaps = at.slice(1).map((t, i) => t - at[i]!);
    expect(gaps).toEqual([1000, 2000, 4000]);
  });

  it("jitters each wait by at most a quarter", async () => {
    const at: number[] = [];
    pollWithBackoff(
      async () => at.push(Date.now()),
      () => false,
      () => {},
      { initialMs: 1000, random: () => 0 },
    );
    await vi.advanceTimersByTimeAsync(1000);
    expect(at[1]! - at[0]!).toBe(750);
  });

  it("stops when cancelled", async () => {
    const fetchOnce = vi.fn(async () => "PENDING");
    const stop = pollWithBackoff(fetchOnce, () => false, () => {});
    await vi.advanceTimersByTimeAsync(0);
    stop();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetchOnce).toHaveBeenCalledTimes(1);
  });

  it("keeps polling after a failed poll", async () => {
    let calls = 0;
    const seen: string[] = [];
    pollWithBackoff(
      async () => {
        if (calls++ === 0) {
          throw new Error("offline");
        }
        return "CONFIRMED";
      },
      (s) => s === "CONFIRMED",
      (s) => seen.push(s),
      { random: () => 1 },
    );
    await vi.advanceTimersByTimeAsync(5000);
    expect(seen).toEqual(["CONFIRMED"]);
  });
});
