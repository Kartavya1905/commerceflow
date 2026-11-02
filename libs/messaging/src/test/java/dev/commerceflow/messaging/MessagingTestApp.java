package dev.commerceflow.messaging;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** A minimal service using the library, with a consumer whose failures tests can switch on. */
@SpringBootApplication
class MessagingTestApp {

  /** Consumes OrderCreated and records each application in applied_events. */
  @Component
  static class TestListener {

    /** Orders whose handling always fails (they should end in the dead-letter topic). */
    static final Set<UUID> ALWAYS_FAIL = ConcurrentHashMap.newKeySet();

    /** Number of upcoming handling attempts that fail before succeeding. */
    static final AtomicInteger FAIL_NEXT = new AtomicInteger();

    static final AtomicInteger ATTEMPTS = new AtomicInteger();

    private final InboundEvents inbound;
    private final JdbcClient jdbc;

    TestListener(InboundEvents inbound, JdbcClient jdbc) {
      this.inbound = inbound;
      this.jdbc = jdbc;
    }

    @KafkaListener(topics = "order.events.v1")
    void on(ConsumerRecord<String, String> record) {
      inbound.handle(
          record,
          "test-consumer",
          event -> {
            ATTEMPTS.incrementAndGet();
            UUID orderId = event.uuid("orderId");
            // Write first, then fail: proves the failed attempt's write is rolled back.
            jdbc.sql("insert into applied_events (event_id, order_id) values (:e, :o)")
                .param("e", event.eventId())
                .param("o", orderId)
                .update();
            if (ALWAYS_FAIL.contains(orderId)
                || FAIL_NEXT.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
              throw new IllegalStateException("injected failure for " + orderId);
            }
          });
    }
  }
}
