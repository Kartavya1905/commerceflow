package dev.commerceflow.messaging;

import dev.commerceflow.contracts.EventContracts;
import dev.commerceflow.contracts.TopicCatalog;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records an event in the outbox, inside the caller's transaction. The event becomes visible to the
 * publisher only if that transaction commits, which is what makes "change the database and publish
 * an event" a single atomic step.
 */
public class OutboxWriter {

  static final String CORRELATION_HEADER = "X-Correlation-Id";
  private static final String MDC_CORRELATION = "correlationId";

  private final JdbcClient jdbc;
  private final EventContracts contracts;
  private final MessagingProperties properties;
  private final Clock clock;
  private final TraceHeaders trace;
  private final JsonMapper json = JsonMapper.builder().build();

  public OutboxWriter(
      JdbcClient jdbc, EventContracts contracts, MessagingProperties properties, Clock clock) {
    this(jdbc, contracts, properties, clock, TraceHeaders.NONE);
  }

  public OutboxWriter(
      JdbcClient jdbc,
      EventContracts contracts,
      MessagingProperties properties,
      Clock clock,
      TraceHeaders trace) {
    this.jdbc = jdbc;
    this.contracts = contracts;
    this.properties = properties;
    this.clock = clock;
    this.trace = trace;
  }

  /**
   * MANDATORY: there must already be a transaction. Writing an outbox row in a transaction of its
   * own would recreate the dual-write problem the outbox exists to solve.
   *
   * <p>The envelope is validated against the event's JSON Schema before it is stored; a violation
   * throws and rolls back the business change with it, so an invalid event is never published.
   *
   * @return the new event id
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID append(
      String eventType, String aggregateType, UUID aggregateId, Map<String, ?> payload) {
    TopicCatalog.Topic topic =
        contracts
            .catalog()
            .topicFor(eventType)
            .orElseThrow(() -> new IllegalArgumentException("Unknown event type " + eventType));

    // Also serialises writers of the same aggregate, so their outbox rows are in commit order.
    long aggregateVersion =
        jdbc.sql(
                """
                insert into aggregate_versions (aggregate_type, aggregate_id, version)
                values (:type, :id, 1)
                on conflict (aggregate_type, aggregate_id)
                do update set version = aggregate_versions.version + 1
                returning version
                """)
            .param("type", aggregateType)
            .param("id", aggregateId)
            .query(Long.class)
            .single();

    UUID eventId = Uuids.v7();
    OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
    String correlationId = MDC.get(MDC_CORRELATION);
    if (correlationId == null) {
      correlationId = "evt-" + eventId;
    }
    UUID causationId = EventContext.causationId();

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId);
    envelope.put("eventType", eventType);
    envelope.put("eventVersion", 1);
    envelope.put("occurredAt", now.toInstant().toString());
    envelope.put("producer", properties.producer());
    envelope.put("aggregateType", aggregateType);
    envelope.put("aggregateId", aggregateId);
    envelope.put("aggregateVersion", aggregateVersion);
    envelope.put("correlationId", correlationId);
    envelope.put("causationId", causationId);
    envelope.put("payload", payload);
    String text = json.writeValueAsString(envelope);

    List<String> violations = contracts.validate(text);
    if (!violations.isEmpty()) {
      throw new IllegalStateException(eventType + " violates its contract: " + violations);
    }

    jdbc.sql(
            """
            insert into outbox_events (id, topic, partition_key, event_type, envelope, headers,
                                       created_at)
            values (:id, :topic, :key, :type, :envelope, cast(:headers as jsonb), :now)
            """)
        .param("id", eventId)
        .param("topic", topic.name())
        .param("key", aggregateId.toString())
        .param("type", eventType)
        .param("envelope", text)
        .param("headers", json.writeValueAsString(headers(correlationId)))
        .param("now", now)
        .update();
    return eventId;
  }

  /** The correlation id, and the trace context of the request that wrote the event. */
  private Map<String, String> headers(String correlationId) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(CORRELATION_HEADER, correlationId);
    trace.inject(headers);
    return headers;
  }
}
