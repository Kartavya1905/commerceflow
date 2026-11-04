package dev.commerceflow.messaging;

import dev.commerceflow.messaging.MessagingProperties.Chaos;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies a consumed event at most once per consumer.
 *
 * <p>The processed-event record and the handler's effects commit in one transaction: either both
 * happen or neither does, so a redelivery after a crash is applied exactly as if it were the first
 * delivery, and a redelivery after success is skipped. The Kafka offset is committed only after
 * this method returns, so nothing is acknowledged that was not committed.
 */
public class InboundEvents {

  private static final String MDC_CORRELATION = "correlationId";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final MeterRegistry meters;
  private final Chaos chaos;
  private final Predicate<String> knownEventType;
  private final JsonMapper json = JsonMapper.builder().build();

  /** A failure injected by {@link Chaos}; retried like any other processing failure. */
  public static final class InjectedFailure extends RuntimeException {
    InjectedFailure(String consumer) {
      super("injected failure in " + consumer + " (commerceflow.messaging.chaos)");
    }
  }

  public InboundEvents(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry meters) {
    this(jdbc, tx, meters, Chaos.NONE);
  }

  public InboundEvents(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry meters, Chaos chaos) {
    this(jdbc, tx, meters, chaos, type -> true);
  }

  /**
   * @param knownEventType whether an event type is in the contract catalogue; any other type is
   *     counted as "other", because a metric label must come from a bounded set
   */
  public InboundEvents(
      JdbcClient jdbc,
      TransactionTemplate tx,
      MeterRegistry meters,
      Chaos chaos,
      Predicate<String> knownEventType) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.meters = meters;
    this.chaos = chaos;
    this.knownEventType = knownEventType;
  }

  /**
   * @param consumer a stable name for this handler; the dedupe key is (consumer, eventId)
   * @param action runs inside the transaction; throw to roll back and let the error handler retry
   */
  public void handle(
      ConsumerRecord<String, String> record, String consumer, Consumer<InboundEvent> action) {
    run(
        record,
        consumer,
        event ->
            tx.execute(
                status -> {
                  int first =
                      jdbc.sql(
                              """
                              insert into processed_events (consumer, event_id, processed_at)
                              values (:consumer, :id, now())
                              on conflict (consumer, event_id) do nothing
                              """)
                          .param("consumer", consumer)
                          .param("id", event.eventId())
                          .update();
                  if (first == 0) {
                    return false;
                  }
                  action.accept(event);
                  return true;
                }));
  }

  /**
   * For a handler that has to call another system, which must never happen inside a transaction: a
   * slow provider would hold a pooled connection, and row locks, for as long as it takes. There is
   * no surrounding transaction and no event-id dedupe here. The handler runs its own short
   * transactions and must be idempotent by business key (payment-service keys on the order). The
   * correlation, causation and metrics are as for {@link #handle}.
   */
  public void handleUntransacted(
      ConsumerRecord<String, String> record, String consumer, Consumer<InboundEvent> action) {
    run(
        record,
        consumer,
        event -> {
          action.accept(event);
          return true;
        });
  }

  private void run(
      ConsumerRecord<String, String> record,
      String consumer,
      Function<InboundEvent, Boolean> body) {
    InboundEvent event;
    try {
      event = parse(record);
    } catch (PoisonMessageException e) {
      // Counted per detection: a poison message must be detected once, not once per retry.
      meters.counter("messaging.poison", "consumer", consumer, "topic", record.topic()).increment();
      throw e;
    }
    // Carried into every event this handler produces, so one id spans the whole workflow.
    MDC.put(
        MDC_CORRELATION,
        event.correlationId() != null ? event.correlationId() : "evt-" + event.eventId());
    EventContext.enter(event.eventId());
    try {
      injectChaos(consumer);
      Boolean applied = body.apply(event);
      meters
          .counter(
              "messaging.consumed",
              "consumer",
              consumer,
              "eventType",
              knownEventType.test(event.eventType()) ? event.eventType() : "other",
              "outcome",
              Boolean.TRUE.equals(applied) ? "processed" : "duplicate")
          .increment();
    } finally {
      EventContext.exit();
      MDC.remove(MDC_CORRELATION);
    }
  }

  private void injectChaos(String consumer) {
    if (!chaos.active()) {
      return;
    }
    if (chaos.consumerDelay().isPositive()) {
      try {
        Thread.sleep(chaos.consumerDelay());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted during an injected delay", e);
      }
    }
    if (ThreadLocalRandom.current().nextDouble() < chaos.consumerFailureRate()) {
      meters.counter("messaging.chaos.failures", "consumer", consumer).increment();
      throw new InjectedFailure(consumer);
    }
  }

  /**
   * Reads the envelope fields every consumer relies on. Payload fields are left to the handler,
   * which reads only what it needs (tolerant reader), so producers can add optional fields.
   */
  InboundEvent parse(ConsumerRecord<String, String> record) {
    if (record.value() == null) {
      throw new PoisonMessageException("empty message at " + where(record));
    }
    JsonNode root;
    try {
      root = json.readTree(record.value());
    } catch (JacksonException e) {
      throw new PoisonMessageException("not JSON at " + where(record), e);
    }
    try {
      String correlationId = root.path("correlationId").asString(null);
      if (correlationId == null) {
        Header header = record.headers().lastHeader(OutboxWriter.CORRELATION_HEADER);
        correlationId = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
      }
      InboundEvent event =
          new InboundEvent(
              UUID.fromString(root.path("eventId").asString()),
              root.path("eventType").asString(),
              root.path("eventVersion").asInt(0),
              UUID.fromString(root.path("aggregateId").asString()),
              correlationId,
              root.path("payload"));
      if (event.eventType().isBlank() || event.eventVersion() < 1 || !event.payload().isObject()) {
        throw new PoisonMessageException("incomplete envelope at " + where(record));
      }
      return event;
    } catch (IllegalArgumentException e) {
      throw new PoisonMessageException("invalid envelope at " + where(record), e);
    }
  }

  private static String where(ConsumerRecord<?, ?> record) {
    return record.topic() + "-" + record.partition() + "@" + record.offset();
  }
}
