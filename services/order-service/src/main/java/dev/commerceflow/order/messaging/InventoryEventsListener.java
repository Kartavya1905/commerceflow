package dev.commerceflow.order.messaging;

import dev.commerceflow.messaging.InboundEvents;
import dev.commerceflow.order.saga.SagaOrchestrator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Inventory's answers to OrderCreated and to the saga's commit and release commands. */
@Component
class InventoryEventsListener {

  private final InboundEvents inbound;
  private final SagaOrchestrator saga;

  InventoryEventsListener(InboundEvents inbound, SagaOrchestrator saga) {
    this.inbound = inbound;
    this.saga = saga;
  }

  @KafkaListener(topics = "inventory.events.v1")
  void on(ConsumerRecord<String, String> record) {
    inbound.handle(
        record,
        "order.inventory-events",
        event -> {
          SagaEvents.requireVersion(event);
          saga.on(event);
        });
  }
}
