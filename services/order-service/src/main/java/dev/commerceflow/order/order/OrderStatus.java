package dev.commerceflow.order.order;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves an order from one status to the next, only if it is still in the expected one. A late,
 * duplicated or out-of-order event therefore changes nothing instead of moving an order backwards.
 * Only the saga calls this, under its row lock; the guard stays as a second line of defence.
 */
@Component
public class OrderStatus {

  private final JdbcClient jdbc;
  private final MeterRegistry meters;
  private final Clock clock;

  OrderStatus(JdbcClient jdbc, MeterRegistry meters, Clock clock) {
    this.jdbc = jdbc;
    this.meters = meters;
    this.clock = clock;
  }

  /**
   * @return true if the order moved; false if it was not in {@code from} (nothing changed)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean transition(
      UUID orderId, String from, String to, String failureReason, UUID causedByEventId) {
    OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
    int moved =
        jdbc.sql(
                """
                update orders
                   set status = :to, failure_reason = :reason, version = version + 1,
                       updated_at = :now
                 where id = :id and status = :from
                """)
            .param("to", to)
            .param("reason", failureReason)
            .param("now", now)
            .param("id", orderId)
            .param("from", from)
            .update();
    if (moved == 0) {
      meters.counter("order.transitions.ignored", "to", to).increment();
      return false;
    }
    jdbc.sql(
            """
            insert into order_status_history
                   (order_id, from_status, to_status, reason, caused_by_event_id, at)
            values (:id, :from, :to, :reason, :event, :now)
            """)
        .param("id", orderId)
        .param("from", from)
        .param("to", to)
        .param("reason", failureReason)
        .param("event", causedByEventId)
        .param("now", now)
        .update();
    meters.counter("order.transitions", "to", to).increment();
    return true;
  }
}
