package dev.commerceflow.messaging;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A consumed event, parsed from its envelope. The payload is read tolerantly by the handler. */
public record InboundEvent(
    UUID eventId,
    String eventType,
    int eventVersion,
    UUID aggregateId,
    String correlationId,
    JsonNode payload) {

  public UUID uuid(String field) {
    return UUID.fromString(payload.path(field).asString());
  }

  public String text(String field) {
    return payload.path(field).asString();
  }
}
