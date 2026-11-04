package dev.commerceflow.order.saga;

import dev.commerceflow.messaging.Backoff;
import dev.commerceflow.messaging.InboundEvent;
import dev.commerceflow.messaging.OutboxWriter;
import dev.commerceflow.messaging.Uuids;
import dev.commerceflow.order.config.OrderConfig.OrderProperties;
import dev.commerceflow.order.order.OrderStatus;
import dev.commerceflow.order.saga.SagaTransitions.Command;
import dev.commerceflow.order.saga.SagaTransitions.Transition;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies {@link SagaTransitions} to one order at a time. Each transition runs inside the
 * consumer's transaction and does all of its work there: the saga row (locked FOR UPDATE, so two
 * events for one order never interleave), the order's status, the next command and any
 * OrderConfirmed or OrderFailed all commit together with the event's dedupe record, or not at all.
 * An event that is not valid in the current state is counted and acknowledged, never thrown:
 * throwing would retry it into the dead-letter topic for no gain.
 */
@Component
public class SagaOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);
  private static final String ORDER = "Order";

  /** The saga row joined with the order fields its messages need. */
  private record Saga(
      UUID id,
      UUID orderId,
      SagaState state,
      String failureReason,
      UUID paymentAttemptId,
      String commandReason,
      int stepAttempts,
      String orderStatus,
      UUID customerId,
      long totalMinor,
      String currency,
      String paymentToken) {}

  private final JdbcClient jdbc;
  private final OrderStatus orderStatus;
  private final OutboxWriter outbox;
  private final MeterRegistry meters;
  private final Clock clock;
  private final OrderProperties properties;

  SagaOrchestrator(
      JdbcClient jdbc,
      OrderStatus orderStatus,
      OutboxWriter outbox,
      MeterRegistry meters,
      Clock clock,
      OrderProperties properties) {
    this.jdbc = jdbc;
    this.orderStatus = orderStatus;
    this.outbox = outbox;
    this.meters = meters;
    this.clock = clock;
    this.properties = properties;
  }

  /** Called in the transaction that saves a new order. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void start(UUID orderId) {
    OffsetDateTime now = now();
    jdbc.sql(
            """
            insert into saga_instances (id, order_id, state, deadline_at, created_at, updated_at)
            values (:id, :order, 'AWAITING_RESERVATION', :deadline, :now, :now)
            """)
        .param("id", Uuids.v7())
        .param("order", orderId)
        .param("deadline", deadline(SagaState.AWAITING_RESERVATION, now))
        .param("now", now)
        .update();
  }

  /** An inventory or payment event, inside the consumer's transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void on(InboundEvent event) {
    Optional<SagaEvent> sagaEvent =
        SagaEvent.of(event.eventType(), event.payload().path("reason").asString(null));
    if (sagaEvent.isEmpty()) {
      // Not the type itself: a metric label must come from a bounded set.
      ignored("unknown_event", "other");
      return;
    }
    apply(event.uuid("orderId"), sagaEvent.get(), event);
  }

  /**
   * @param cause the event that triggered this, or null (a deadline)
   * @return true if the saga moved
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean apply(UUID orderId, SagaEvent sagaEvent, InboundEvent cause) {
    Optional<Saga> found = lock(orderId);
    if (found.isEmpty()) {
      ignored("no_saga", sagaEvent.name());
      return false;
    }
    Saga saga = found.get();
    Optional<Transition> next = SagaTransitions.next(saga.state(), sagaEvent);
    if (next.isEmpty()) {
      // A duplicate, late or out-of-order event: the saga has already moved past it.
      ignored(saga.state().terminal() ? "terminal" : "invalid_for_state", sagaEvent.name());
      log.info("Saga {} in {} ignored {}", orderId, saga.state(), sagaEvent);
      return false;
    }
    Transition transition = next.get();
    String failureReason =
        transition.failureReason() != null ? transition.failureReason() : saga.failureReason();
    UUID paymentAttemptId =
        sagaEvent == SagaEvent.PAYMENT_SUCCEEDED
            ? cause.uuid("paymentAttemptId")
            : saga.paymentAttemptId();
    UUID causeId = cause == null ? null : cause.eventId();

    move(saga, transition, failureReason, paymentAttemptId);
    if (transition.orderStatus() != null) {
      String reason = transition.to() == SagaState.FAILED ? failureReason : null;
      // The saga row lock serializes every change to this order, so the guard cannot miss.
      if (!orderStatus.transition(
          orderId, saga.orderStatus(), transition.orderStatus(), reason, causeId)) {
        throw new IllegalStateException(
            "order " + orderId + " was not " + saga.orderStatus() + " as its saga expected");
      }
    }
    send(saga, transition.command(), transition.commandReason());
    if (transition.to() == SagaState.COMPLETED) {
      outbox.append(
          "OrderConfirmed",
          ORDER,
          orderId,
          Map.of(
              "orderId", orderId,
              "customerId", saga.customerId(),
              "totalMinor", saga.totalMinor(),
              "currency", saga.currency(),
              "paymentAttemptId", paymentAttemptId));
    } else if (transition.to() == SagaState.FAILED) {
      outbox.append(
          "OrderFailed",
          ORDER,
          orderId,
          Map.of("orderId", orderId, "customerId", saga.customerId(), "reason", failureReason));
    }
    meters
        .counter("saga.transitions", "from", saga.state().name(), "to", transition.to().name())
        .increment();
    return true;
  }

  private Optional<Saga> lock(UUID orderId) {
    return select(orderId, "for update of s");
  }

  /** Locks the saga only if it is still overdue and no one else holds it. */
  private Optional<Saga> lockIfDue(UUID orderId) {
    return select(orderId, "and s.deadline_at <= :now for update of s skip locked");
  }

  private Optional<Saga> select(UUID orderId, String condition) {
    String sql =
        """
        select s.id, s.order_id, s.state, s.failure_reason, s.payment_attempt_id,
               s.command_reason, s.step_attempts,
               o.status, o.customer_id, o.total_minor, o.currency, o.payment_token
          from saga_instances s
          join orders o on o.id = s.order_id
         where s.order_id = :order
        """
            + condition;
    var statement = jdbc.sql(sql).param("order", orderId);
    if (condition.contains(":now")) {
      statement = statement.param("now", now());
    }
    return statement
        .query(
            (rs, n) ->
                new Saga(
                    rs.getObject("id", UUID.class),
                    rs.getObject("order_id", UUID.class),
                    SagaState.valueOf(rs.getString("state")),
                    rs.getString("failure_reason"),
                    rs.getObject("payment_attempt_id", UUID.class),
                    rs.getString("command_reason"),
                    rs.getInt("step_attempts"),
                    rs.getString("status"),
                    rs.getObject("customer_id", UUID.class),
                    rs.getLong("total_minor"),
                    rs.getString("currency"),
                    rs.getString("payment_token")))
        .optional();
  }

  private void move(Saga saga, Transition transition, String failureReason, UUID paymentAttemptId) {
    SagaState to = transition.to();
    OffsetDateTime now = now();
    int moved =
        jdbc.sql(
                """
                update saga_instances
                   set state = :to, failure_reason = :reason, payment_attempt_id = :attempt,
                       command_reason = :commandReason, step_attempts = 0,
                       deadline_at = :deadline, version = version + 1, updated_at = :now
                 where id = :id and state = :from
                """)
            .param("to", to.name())
            .param("reason", failureReason)
            .param("commandReason", transition.commandReason())
            .param("attempt", paymentAttemptId)
            .param("deadline", deadline(to, now))
            .param("now", now)
            .param("id", saga.id())
            .param("from", saga.state().name())
            .update();
    if (moved != 1) {
      throw new IllegalStateException("saga " + saga.id() + " changed under its row lock");
    }
  }

  private void send(Saga saga, Command command, String reason) {
    UUID orderId = saga.orderId();
    switch (command) {
      case NONE -> {}
      case RESERVE_INVENTORY -> outbox.append("OrderCreated", ORDER, orderId, orderCreated(saga));
      case AUTHORIZE_PAYMENT -> {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("customerId", saga.customerId());
        payload.put("amountMinor", saga.totalMinor());
        payload.put("currency", saga.currency());
        payload.put("paymentToken", saga.paymentToken());
        outbox.append("AuthorizePayment", ORDER, orderId, payload);
      }
      case COMMIT_INVENTORY ->
          outbox.append("CommitInventory", ORDER, orderId, Map.of("orderId", orderId));
      case RELEASE_INVENTORY ->
          outbox.append(
              "ReleaseInventory", ORDER, orderId, Map.of("orderId", orderId, "reason", reason));
      case CANCEL_PAYMENT ->
          outbox.append(
              "CancelPayment", ORDER, orderId, Map.of("orderId", orderId, "reason", reason));
    }
  }

  /** What the deadline sweeper did with one saga. */
  public enum Swept {
    /** No longer overdue, or locked by an event handler right now: left for the next round. */
    SKIPPED,
    /** The step's command was sent again. */
    RESENT,
    /** Out of re-sends: the saga took its deadline transition and started compensating. */
    GAVE_UP,
    /** Out of re-sends in a step that has no safe way out; still re-sending, and alerting. */
    STUCK,
    /** Sweeping it threw and was rolled back; it stays overdue and is tried next round. */
    ERROR
  }

  /**
   * One overdue saga, in its own transaction. Re-sends the step's command (every receiver is
   * idempotent by order id, so a re-send is answered, never applied twice) with exponential backoff
   * and full jitter, up to {@code maxResends}. Then, if the state has a deadline transition, takes
   * it: compensation runs whether or not the step happened, because each compensating command is a
   * no-op when there is nothing to undo. A step without one (committing stock, releasing it,
   * cancelling a payment) is itself the way out, so it keeps being re-sent, and counts as stuck.
   *
   * <p>The row is locked with SKIP LOCKED, so an event being applied to the saga right now wins and
   * the sweeper looks again next round; the two can never both change it.
   */
  @Transactional
  public Swept sweep(UUID orderId) {
    Optional<Saga> found = lockIfDue(orderId);
    if (found.isEmpty()) {
      return Swept.SKIPPED;
    }
    Saga saga = found.get();
    Command step = SagaTransitions.stepCommand(saga.state());
    int maxResends = properties.sagaMaxResends();
    boolean canGiveUp = SagaTransitions.next(saga.state(), SagaEvent.DEADLINE_EXCEEDED).isPresent();
    if (canGiveUp && (step == Command.NONE || saga.stepAttempts() >= maxResends)) {
      log.warn(
          "Saga {} gave up on {} after {} re-sends",
          saga.orderId(),
          saga.state(),
          saga.stepAttempts());
      meters.counter("saga.deadline_exceeded", "state", saga.state().name()).increment();
      apply(saga.orderId(), SagaEvent.DEADLINE_EXCEEDED, null);
      return Swept.GAVE_UP;
    }
    send(saga, step, resendReason(step, saga.commandReason()));
    int attempts = saga.stepAttempts() + 1;
    OffsetDateTime now = now();
    jdbc.sql(
            """
            update saga_instances
               set step_attempts = :attempts, deadline_at = :deadline, version = version + 1,
                   updated_at = :now
             where id = :id
            """)
        .param("attempts", attempts)
        .param(
            "deadline",
            now.plus(
                Backoff.fullJitter(
                    attempts - 1, properties.sagaResendBase(), properties.sagaResendMax())))
        .param("now", now)
        .param("id", saga.id())
        .update();
    boolean stuck = attempts > maxResends;
    meters
        .counter("saga.resends", "state", saga.state().name(), "stuck", String.valueOf(stuck))
        .increment();
    if (stuck) {
      log.error(
          "Saga {} is stuck in {}: re-send {} of {} has had no answer",
          saga.orderId(),
          saga.state(),
          attempts,
          step);
      return Swept.STUCK;
    }
    log.info("Saga {} re-sent {} ({} of {})", saga.orderId(), step, attempts, maxResends);
    return Swept.RESENT;
  }

  /** Overdue sagas, most overdue first. Each is then swept in its own transaction. */
  @Transactional(readOnly = true)
  public List<UUID> overdue(int limit) {
    return jdbc.sql(
            """
            select order_id from saga_instances
             where deadline_at <= :now
             order by deadline_at
             limit :limit
            """)
        .param("now", now())
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  /** Sagas past their last re-send in a step with no way out: what the stuck-saga alert counts. */
  @Transactional(readOnly = true)
  public long stuck() {
    return jdbc.sql(
            """
            select count(*) from saga_instances
             where deadline_at is not null and step_attempts > :max
            """)
        .param("max", properties.sagaMaxResends())
        .query(Long.class)
        .single();
  }

  /** How many sagas are in each state now; states with none are absent. */
  @Transactional(readOnly = true)
  public Map<SagaState, Long> countByState() {
    Map<SagaState, Long> counts = new java.util.EnumMap<>(SagaState.class);
    jdbc.sql("select state, count(*) as n from saga_instances group by state")
        .query((rs, n) -> counts.put(SagaState.valueOf(rs.getString("state")), rs.getLong("n")))
        .list();
    return counts;
  }

  /** Sagas saved before the reason was recorded re-send with the reason their step implies. */
  private static String resendReason(Command command, String recorded) {
    if (recorded != null) {
      return recorded;
    }
    return switch (command) {
      case RELEASE_INVENTORY -> "RESERVATION_TIMEOUT";
      case CANCEL_PAYMENT -> "SAGA_TIMEOUT";
      default -> null;
    };
  }

  /** OrderCreated again, from what was saved: the same request inventory saw the first time. */
  private Map<String, Object> orderCreated(Saga saga) {
    List<Map<String, Object>> items =
        jdbc.sql(
                """
                select product_id, quantity, unit_price_minor from order_items
                 where order_id = :order
                 order by product_id
                """)
            .param("order", saga.orderId())
            .query(
                (rs, n) ->
                    Map.<String, Object>of(
                        "productId", rs.getObject("product_id", UUID.class),
                        "quantity", rs.getInt("quantity"),
                        "unitPriceMinor", rs.getLong("unit_price_minor")))
            .list();
    return Map.of(
        "orderId", saga.orderId(),
        "customerId", saga.customerId(),
        "items", items,
        "totalMinor", saga.totalMinor(),
        "currency", saga.currency());
  }

  private OffsetDateTime deadline(SagaState state, OffsetDateTime now) {
    if (state.terminal()) {
      return null;
    }
    return now.plus(
        state == SagaState.AWAITING_RECONCILIATION
            ? properties.sagaReconciliationTimeout()
            : properties.sagaStepTimeout());
  }

  private void ignored(String reason, String event) {
    meters.counter("saga.event.ignored", "reason", reason, "event", event).increment();
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
  }
}
