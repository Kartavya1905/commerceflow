package dev.commerceflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** handleUntransacted: the context a handler relies on, without a transaction or dedupe. */
class InboundEventsTest {

  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  // No JdbcClient or TransactionTemplate: the untransacted path must not need either.
  final InboundEvents inbound = new InboundEvents(null, null, meters);

  @Test
  void runsTheHandlerOutsideATransactionWithCausationAndCorrelationSet() {
    UUID eventId = UUID.randomUUID();
    AtomicReference<Boolean> inTransaction = new AtomicReference<>();
    AtomicReference<UUID> cause = new AtomicReference<>();
    AtomicReference<String> correlation = new AtomicReference<>();

    inbound.handleUntransacted(
        record(eventId),
        "test",
        event -> {
          inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
          cause.set(EventContext.causationId());
          correlation.set(MDC.get("correlationId"));
        });

    assertThat(inTransaction.get()).isFalse();
    assertThat(cause.get()).isEqualTo(eventId);
    assertThat(correlation.get()).isEqualTo("corr-1");
    // Nothing leaks into whatever this thread handles next.
    assertThat(EventContext.causationId()).isNull();
    assertThat(MDC.get("correlationId")).isNull();
    assertThat(
            meters
                .counter(
                    "messaging.consumed",
                    "consumer",
                    "test",
                    "eventType",
                    "AuthorizePayment",
                    "outcome",
                    "processed")
                .count())
        .isEqualTo(1);
  }

  @Test
  void aPoisonMessageIsStillDetectedAndCounted() {
    ConsumerRecord<String, String> junk =
        new ConsumerRecord<>("payment.commands.v1", 0, 0, "k", "not json");
    assertThatThrownBy(() -> inbound.handleUntransacted(junk, "test", event -> {}))
        .isInstanceOf(PoisonMessageException.class);
    assertThat(
            meters
                .counter("messaging.poison", "consumer", "test", "topic", "payment.commands.v1")
                .count())
        .isEqualTo(1);
  }

  static ConsumerRecord<String, String> record(UUID eventId) {
    UUID orderId = UUID.randomUUID();
    String json =
        """
        {"eventId":"%s","eventType":"AuthorizePayment","eventVersion":1,
         "aggregateId":"%s","correlationId":"corr-1","payload":{"orderId":"%s"}}
        """
            .formatted(eventId, orderId, orderId);
    return new ConsumerRecord<>("payment.commands.v1", 0, 0, orderId.toString(), json);
  }
}
