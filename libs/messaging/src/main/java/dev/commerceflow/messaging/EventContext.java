package dev.commerceflow.messaging;

import java.util.UUID;

/**
 * The event being handled on this thread, if any. OutboxWriter reads it so that an event produced
 * in reaction to another records it as its causationId.
 */
public final class EventContext {

  private static final ThreadLocal<UUID> CAUSE = new ThreadLocal<>();

  private EventContext() {}

  static void enter(UUID eventId) {
    CAUSE.set(eventId);
  }

  static void exit() {
    CAUSE.remove();
  }

  /** The id of the event being handled, or null outside a consumer. */
  public static UUID causationId() {
    return CAUSE.get();
  }
}
