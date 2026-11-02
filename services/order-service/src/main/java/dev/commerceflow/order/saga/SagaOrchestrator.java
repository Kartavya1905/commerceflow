package dev.commerceflow.order.saga;

import dev.commerceflow.messaging.InboundEvent;
import dev.commerceflow.messaging.OutboxWriter;
import dev.commerceflow.messaging.Uuids;
import dev.commerceflow.order.config.OrderConfig.OrderProperties;
import dev.commerceflow.order.order.OrderStatus;
import dev.commerceflow.order.saga.SagaTransitions.Transition;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
      ignored("unknown_event", event.eventType());
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

    move(saga, transition.to(), failureReason, paymentAttemptId);
    if (transition.orderStatus() != null) {
      String reason = transition.to() == SagaState.FAILED ? failureReason : null;
      // The saga row lock serializes every change to this order, so the guard cannot miss.
      if (!orderStatus.transition(
          orderId, saga.orderStatus(), transition.orderStatus(), reason, causeId)) {
        throw new IllegalStateException(
            "order " + orderId + " was not " + saga.orderStatus() + " as its saga expected");
      }
    }
    send(saga, transition);
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
    return jdbc.sql(
            """
            select s.id, s.order_id, s.state, s.failure_reason, s.payment_attempt_id,
                   o.status, o.customer_id, o.total_minor, o.currency, o.payment_token
              from saga_instances s
              join orders o on o.id = s.order_id
             where s.order_id = :order
               for update of s
            """)
        .param("order", orderId)
        .query(
            (rs, n) ->
                new Saga(
                    rs.getObject("id", UUID.class),
                    rs.getObject("order_id", UUID.class),
                    SagaState.valueOf(rs.getString("state")),
                    rs.getString("failure_reason"),
                    rs.getObject("payment_attempt_id", UUID.class),
                    rs.getString("status"),
                    rs.getObject("customer_id", UUID.class),
                    rs.getLong("total_minor"),
                    rs.getString("currency"),
                    rs.getString("payment_token")))
        .optional();
  }

  private void move(Saga saga, SagaState to, String failureReason, UUID paymentAttemptId) {
    OffsetDateTime now = now();
    int moved =
        jdbc.sql(
                """
                update saga_instances
                   set state = :to, failure_reason = :reason, payment_attempt_id = :attempt,
                       step_attempts = 0, deadline_at = :deadline, version = version + 1,
                       updated_at = :now
                 where id = :id and state = :from
                """)
            .param("to", to.name())
            .param("reason", failureReason)
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

  private void send(Saga saga, Transition transition) {
    UUID orderId = saga.orderId();
    switch (transition.command()) {
      case NONE -> {}
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
              "ReleaseInventory",
              ORDER,
              orderId,
              Map.of("orderId", orderId, "reason", transition.commandReason()));
      case CANCEL_PAYMENT ->
          outbox.append(
              "CancelPayment",
              ORDER,
              orderId,
              Map.of("orderId", orderId, "reason", transition.commandReason()));
    }
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
