package dev.commerceflow.messaging;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** A minimal service using the library, with a consumer whose failures tests can switch on. */
@SpringBootApplication
class MessagingTestApp {

  /** A fixed trace context, so tests can follow it from the outbox row to the Kafka record. */
  static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  // Primary: this app also scans the auto-configuration's package, so both are registered.
  @org.springframework.context.annotation.Bean
  @org.springframework.context.annotation.Primary
  TraceHeaders fixedTraceHeaders() {
    return headers -> headers.put("traceparent", TRACEPARENT);
  }

  /** Consumes OrderCreated and records each application in applied_events. */
  @Component
  static class TestListener {

    /** Orders whose handling always fails (they should end in the dead-letter topic). */
    static final Set<UUID> ALWAYS_FAIL = ConcurrentHashMap.newKeySet();

    /** Number of upcoming handling attempts that fail before succeeding. */
    static final AtomicInteger FAIL_NEXT = new AtomicInteger();

    /** Number of upcoming attempts that fail as if the database were unreachable. */
    static final AtomicInteger OUTAGE_NEXT = new AtomicInteger();

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
            if (OUTAGE_NEXT.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
              throw new DataAccessResourceFailureException("injected: database unreachable");
            }
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
