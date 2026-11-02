package dev.commerceflow.order.messaging;

import dev.commerceflow.messaging.InboundEvents;
import dev.commerceflow.order.saga.SagaOrchestrator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** payment-service's answers to AuthorizePayment and CancelPayment. */
@Component
class PaymentEventsListener {

  private final InboundEvents inbound;
  private final SagaOrchestrator saga;

  PaymentEventsListener(InboundEvents inbound, SagaOrchestrator saga) {
    this.inbound = inbound;
    this.saga = saga;
  }

  @KafkaListener(topics = "payment.events.v1")
  void on(ConsumerRecord<String, String> record) {
    inbound.handle(
        record,
        "order.payment-events",
        event -> {
          SagaEvents.requireVersion(event);
          saga.on(event);
        });
  }
}
