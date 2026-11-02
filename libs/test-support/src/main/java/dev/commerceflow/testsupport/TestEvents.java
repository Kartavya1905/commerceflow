package dev.commerceflow.testsupport;

import dev.commerceflow.contracts.EventContracts;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds envelopes as another service would publish them, for feeding a consumer under test. Every
 * envelope is checked against its contract, so a test cannot pass by sending something no real
 * producer could send.
 */
public final class TestEvents {

  private static final EventContracts CONTRACTS = EventContracts.load();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AtomicLong VERSION = new AtomicLong();

  private TestEvents() {}

  public record Event(UUID eventId, String json) {}

  public static Event envelope(
      String eventType,
      String producer,
      String aggregateType,
      UUID aggregateId,
      Map<String, ?> payload) {
    return envelope(UUID.randomUUID(), eventType, producer, aggregateType, aggregateId, payload);
  }

  /** With a chosen event id, to simulate redelivery of the same event. */
  public static Event envelope(
      UUID eventId,
      String eventType,
      String producer,
      String aggregateType,
      UUID aggregateId,
      Map<String, ?> payload) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId);
    envelope.put("eventType", eventType);
    envelope.put("eventVersion", 1);
    envelope.put("occurredAt", Instant.now().toString());
    envelope.put("producer", producer);
    envelope.put("aggregateType", aggregateType);
    envelope.put("aggregateId", aggregateId);
    envelope.put("aggregateVersion", VERSION.incrementAndGet());
    envelope.put("correlationId", "test-" + eventId);
    envelope.put("causationId", null);
    envelope.put("payload", payload);
    String json = JSON.writeValueAsString(envelope);
    List<String> violations = CONTRACTS.validate(json);
    if (!violations.isEmpty()) {
      throw new IllegalArgumentException("test event breaks its contract: " + violations);
    }
    return new Event(eventId, json);
  }
}
