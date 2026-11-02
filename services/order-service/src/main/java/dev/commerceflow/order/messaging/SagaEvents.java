package dev.commerceflow.order.messaging;

import dev.commerceflow.messaging.InboundEvent;
import dev.commerceflow.messaging.PoisonMessageException;

final class SagaEvents {

  private SagaEvents() {}

  /** A version this code cannot read is poison: retrying will never make it readable. */
  static void requireVersion(InboundEvent event) {
    if (event.eventVersion() != 1) {
      throw new PoisonMessageException(
          "unsupported " + event.eventType() + " version " + event.eventVersion());
    }
  }
}
