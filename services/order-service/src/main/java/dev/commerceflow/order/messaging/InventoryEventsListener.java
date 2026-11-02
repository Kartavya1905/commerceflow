package dev.commerceflow.order.messaging;

import dev.commerceflow.messaging.InboundEvents;
import dev.commerceflow.messaging.PoisonMessageException;
import dev.commerceflow.order.order.OrderStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inventory's answer to OrderCreated decides the order's next status. Milestone 6 replaces the
 * direct status changes with the saga, which also requests payment and handles commit, release and
 * their failures; until then those events are received and acknowledged without effect.
 */
@Component
class InventoryEventsListener {

  private final InboundEvents inbound;
  private final OrderStatus status;

  InventoryEventsListener(InboundEvents inbound, OrderStatus status) {
    this.inbound = inbound;
    this.status = status;
  }

  @KafkaListener(topics = "inventory.events.v1")
  void on(ConsumerRecord<String, String> record) {
    inbound.handle(
        record,
        "order.inventory-events",
        event -> {
          if (event.eventVersion() != 1) {
            throw new PoisonMessageException(
                "unsupported " + event.eventType() + " version " + event.eventVersion());
          }
          switch (event.eventType()) {
            case "InventoryReserved" ->
                status.transition(
                    event.uuid("orderId"), "PENDING", "PAYMENT_PROCESSING", null, event.eventId());
            // UNKNOWN_PRODUCT also means "cannot be supplied": the customer sees OUT_OF_STOCK.
            case "InventoryRejected" ->
                status.transition(
                    event.uuid("orderId"), "PENDING", "FAILED", "OUT_OF_STOCK", event.eventId());
            default -> {
              // InventoryCommitted, InventoryCommitFailed, InventoryReleased: the saga's (M6).
            }
          }
        });
  }
}
