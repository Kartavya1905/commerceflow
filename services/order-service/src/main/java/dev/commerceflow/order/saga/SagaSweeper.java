package dev.commerceflow.order.saga;

import dev.commerceflow.order.config.OrderConfig.OrderProperties;
import dev.commerceflow.order.saga.SagaOrchestrator.Swept;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * The saga deadline sweeper (ARCHITECTURE.md E.2 rule 3, G.4): every few seconds, each overdue saga
 * is re-sent its step's command or, out of re-sends, compensated. This is what makes every accepted
 * order finish (INV-6) even when a message was lost or parked in a dead-letter topic. Safe with
 * several order-service instances: each saga is taken with SKIP LOCKED.
 */
@Component
public class SagaSweeper {

  private static final Logger log = LoggerFactory.getLogger(SagaSweeper.class);

  private final SagaOrchestrator saga;
  private final OrderProperties properties;
  private final MeterRegistry meters;
  private final AtomicLong stuck = new AtomicLong();
  private final Map<SagaState, AtomicLong> byState = new EnumMap<>(SagaState.class);

  SagaSweeper(SagaOrchestrator saga, OrderProperties properties, MeterRegistry meters) {
    this.saga = saga;
    this.properties = properties;
    this.meters = meters;
    Gauge.builder("saga.stuck", stuck, AtomicLong::get)
        .description("Sagas past their last re-send in a step that has no way out")
        .register(meters);
    // One series per state, a fixed set: what the saga dashboard shows waiting at each step.
    for (SagaState state : SagaState.values()) {
      AtomicLong count = new AtomicLong();
      byState.put(state, count);
      Gauge.builder("saga.instances", count, AtomicLong::get)
          .tag("state", state.name())
          .description("Sagas in this state, as of the last sweep")
          .register(meters);
    }
  }

  @Scheduled(
      fixedDelayString = "${commerceflow.order.saga-sweep-interval}",
      initialDelayString = "${commerceflow.order.saga-sweep-interval}")
  void scheduled() {
    try {
      sweep();
    } catch (DataAccessException | TransactionException e) {
      // The database is away. Nothing was changed; the next round tries again.
      log.warn("Saga sweep skipped: {}", e.getMessage());
    }
  }

  /**
   * @return how many sagas each outcome had in this round
   */
  public Map<Swept, Integer> sweep() {
    Map<Swept, Integer> outcomes = new EnumMap<>(Swept.class);
    for (UUID orderId : saga.overdue(properties.sagaSweepBatch())) {
      Swept outcome;
      try {
        outcome = saga.sweep(orderId);
      } catch (DataAccessException | TransactionException e) {
        throw e;
      } catch (RuntimeException e) {
        // Rolled back. One saga that cannot be swept must not stop the others being swept.
        log.error("Sweeping saga {} failed; trying again next round", orderId, e);
        meters.counter("saga.sweep.errors").increment();
        outcome = Swept.ERROR;
      }
      outcomes.merge(outcome, 1, Integer::sum);
    }
    stuck.set(saga.stuck());
    Map<SagaState, Long> counts = saga.countByState();
    byState.forEach((state, count) -> count.set(counts.getOrDefault(state, 0L)));
    return outcomes;
  }
}
