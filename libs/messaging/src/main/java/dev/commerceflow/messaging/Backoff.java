package dev.commerceflow.messaging;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Exponential backoff with full jitter: the n-th wait is a random time between zero and min(max,
 * initial * 2^n). Many callers that failed at the same moment (every consumer when the database
 * went away, every saga whose deadline passed during an outage) then come back spread out, instead
 * of hitting the recovering dependency together on the same schedule.
 */
public final class Backoff {

  private Backoff() {}

  /**
   * @param attempt how many times this has already been retried, from 0
   */
  public static Duration fullJitter(int attempt, Duration initial, Duration max) {
    return fullJitter(attempt, initial, max, ThreadLocalRandom.current());
  }

  static Duration fullJitter(int attempt, Duration initial, Duration max, RandomGenerator random) {
    return Duration.ofMillis(random.nextLong(ceiling(attempt, initial, max) + 1));
  }

  /** The upper bound of the n-th wait, before jitter. */
  static long ceiling(int attempt, Duration initial, Duration max) {
    long ceiling = Math.max(1, initial.toMillis());
    long cap = Math.max(ceiling, max.toMillis());
    for (int i = 0; i < attempt && ceiling < cap; i++) {
      ceiling *= 2;
    }
    return Math.min(ceiling, cap);
  }

  /**
   * The same schedule for Spring Kafka's error handler.
   *
   * @param retries retries after the first attempt; negative for no limit
   */
  public static BackOff retries(Duration initial, Duration max, int retries) {
    return () ->
        new BackOffExecution() {
          private int attempt;

          @Override
          public long nextBackOff() {
            if (retries >= 0 && attempt >= retries) {
              return STOP;
            }
            return fullJitter(attempt++, initial, max).toMillis();
          }
        };
  }
}
