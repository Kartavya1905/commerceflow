package dev.commerceflow.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
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
  private final JsonMapper json = JsonMapper.builder().build();

  public InboundEvents(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.meters = meters;
  }

  /**
   * @param consumer a stable name for this handler; the dedupe key is (consumer, eventId)
   * @param action runs inside the transaction; throw to roll back and let the error handler retry
   */
  public void handle(
      ConsumerRecord<String, String> record, String consumer, Consumer<InboundEvent> action) {
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
      Boolean applied =
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
              });
      meters
          .counter(
              "messaging.consumed",
              "consumer",
              consumer,
              "eventType",
              event.eventType(),
              "outcome",
              Boolean.TRUE.equals(applied) ? "processed" : "duplicate")
          .increment();
    } finally {
      EventContext.exit();
      MDC.remove(MDC_CORRELATION);
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
