package dev.commerceflow.payment.messaging;

import dev.commerceflow.messaging.InboundEvents;
import dev.commerceflow.messaging.PoisonMessageException;
import dev.commerceflow.payment.payment.PaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The saga's payment commands. Handled without a surrounding transaction because handling them
 * calls the provider; idempotency comes from the one-attempt-per-order row instead of event-id
 * dedupe, which also means a re-sent command is answered again rather than silently dropped.
 */
@Component
class PaymentCommandsListener {

  private static final Logger log = LoggerFactory.getLogger(PaymentCommandsListener.class);

  private final InboundEvents inbound;
  private final PaymentService payments;
  private final MeterRegistry meters;

  PaymentCommandsListener(InboundEvents inbound, PaymentService payments, MeterRegistry meters) {
    this.inbound = inbound;
    this.payments = payments;
    this.meters = meters;
  }

  @KafkaListener(topics = "payment.commands.v1")
  void on(ConsumerRecord<String, String> record) {
    inbound.handleUntransacted(
        record,
        "payment.commands",
        event -> {
          if (event.eventVersion() != 1) {
            throw new PoisonMessageException(
                "unsupported " + event.eventType() + " version " + event.eventVersion());
          }
          switch (event.eventType()) {
            case "AuthorizePayment" ->
                payments.authorize(
                    event.uuid("orderId"),
                    event.uuid("customerId"),
                    event.payload().path("amountMinor").asLong(),
                    event.text("currency"),
                    event.text("paymentToken"));
            case "CancelPayment" -> payments.cancel(event.uuid("orderId"));
            default -> {
              // The type goes to the log, not a metric label: labels must come from a bounded set.
              meters.counter("payment.commands.unknown").increment();
              log.warn("Unknown payment command type {}", event.eventType());
            }
          }
        });
  }
}
