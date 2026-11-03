/**
 * Polls until {@code done} says so, waiting longer each time: initial, 2x, 4x... up to max, each
 * wait jittered by up to a quarter (ROADMAP M8 gate: no fixed-rate polling). Many open order pages
 * after an outage would otherwise all hit order-service on the same beat.
 *
 * @returns a function that stops polling (call it on unmount)
 */
export function pollWithBackoff<T>(
  fetchOnce: () => Promise<T>,
  done: (value: T) => boolean,
  onValue: (value: T) => void,
  options: { initialMs?: number; maxMs?: number; random?: () => number } = {},
): () => void {
  const initial = options.initialMs ?? 1000;
  const max = options.maxMs ?? 15_000;
  const random = options.random ?? Math.random;
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let attempt = 0;

  const tick = async () => {
    if (stopped) {
      return;
    }
    try {
      const value = await fetchOnce();
      if (stopped) {
        return;
      }
      onValue(value);
      if (done(value)) {
        return;
      }
    } catch {
      // A failed poll is retried on the same growing schedule.
    }
    const base = Math.min(max, initial * 2 ** attempt);
    attempt++;
    timer = setTimeout(tick, base * (0.75 + random() * 0.25));
  };

  void tick();
  return () => {
    stopped = true;
    if (timer !== undefined) {
      clearTimeout(timer);
    }
  };
}

export function delays(count: number, initial = 1000, max = 15_000): number[] {
  return Array.from({ length: count }, (_, n) => Math.min(max, initial * 2 ** n));
}
