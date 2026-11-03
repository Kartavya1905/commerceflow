package dev.commerceflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.commerceflow.messaging.MessagingTestApp.TestListener;
import dev.commerceflow.testsupport.Containers;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(classes = MessagingTestApp.class)
@Import({Containers.Postgres.class, Containers.Kafka.class})
class OutboxAndInboxIT {

  static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired OutboxWriter outbox;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired KafkaContainer kafkaContainer;
  @Autowired MeterRegistry meters;
  @Autowired OutboxPublisher publisher;
  @Autowired KafkaListenerEndpointRegistry registry;
  @Autowired DeadLetters parked;

  @BeforeEach
  void reset() {
    TestListener.ALWAYS_FAIL.clear();
    TestListener.FAIL_NEXT.set(0);
    TestListener.OUTAGE_NEXT.set(0);
  }

  // --- Writing -------------------------------------------------------------------------------

  @Test
  void appendingOutsideATransactionIsRefused() {
    assertThatThrownBy(
            () ->
                outbox.append(
                    "OrderCreated", "Order", UUID.randomUUID(), payload(UUID.randomUUID())))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void aRolledBackChangeLeavesNoEvent() {
    UUID order = UUID.randomUUID();
    tx.executeWithoutResult(
        status -> {
          outbox.append("OrderCreated", "Order", order, payload(order));
          status.setRollbackOnly();
        });
    assertThat(outboxRowsFor(order)).isZero();
  }

  @Test
  void anEventThatBreaksItsContractIsNeverWritten() {
    UUID order = UUID.randomUUID();
    Map<String, Object> invalid = Map.of("orderId", order, "customerId", UUID.randomUUID());
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> outbox.append("OrderCreated", "Order", order, invalid)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("violates its contract");
    assertThat(outboxRowsFor(order)).isZero();
  }

  // --- Publishing and consuming --------------------------------------------------------------

  @Test
  void eventsForOneOrderArriveInOrderAndEachIsAppliedOnce() {
    UUID order = UUID.randomUUID();
    List<UUID> written = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      written.add(tx.execute(s -> outbox.append("OrderCreated", "Order", order, payload(order))));
    }

    await().atMost(WAIT).until(() -> appliedFor(order).size() == 5);
    assertThat(appliedFor(order)).containsExactlyElementsOf(written);
    assertThat(aggregateVersions(order)).containsExactly(1L, 2L, 3L, 4L, 5L);
  }

  /** A crash after Kafka acknowledged a row but before it was marked: the row is sent again. */
  @Test
  void aRepublishedEventIsDeliveredTwiceButAppliedOnce() {
    UUID order = UUID.randomUUID();
    UUID eventId = tx.execute(s -> outbox.append("OrderCreated", "Order", order, payload(order)));
    await().atMost(WAIT).until(() -> appliedFor(order).size() == 1);
    double duplicatesBefore = duplicates();

    jdbc.sql("update outbox_events set published_at = null where id = ?").param(eventId).update();

    await().atMost(WAIT).until(() -> duplicates() > duplicatesBefore);
    assertThat(appliedFor(order)).containsExactly(eventId);
  }

  @Test
  void aTransientFailureIsRetriedAndTheFailedAttemptLeavesNoTrace() {
    UUID order = UUID.randomUUID();
    TestListener.FAIL_NEXT.set(2);
    tx.executeWithoutResult(s -> outbox.append("OrderCreated", "Order", order, payload(order)));

    await().atMost(WAIT).until(() -> appliedFor(order).size() == 1);
    // The two failed attempts also inserted a row each, and both were rolled back.
    assertThat(appliedFor(order)).hasSize(1);
  }

  /**
   * More database failures than the retry limit (3 attempts here): a processing failure would be
   * dead-lettered by now, but an outage is retried until the database answers, then applied.
   */
  @Test
  void aDatabaseOutageIsRetriedPastTheLimitAndNeverDeadLettered() {
    UUID order = UUID.randomUUID();
    TestListener.OUTAGE_NEXT.set(6);
    tx.executeWithoutResult(s -> outbox.append("OrderCreated", "Order", order, payload(order)));

    await().atMost(WAIT).until(() -> appliedFor(order).size() == 1);
    assertThat(TestListener.OUTAGE_NEXT.get()).isZero();
    List<DeadLetters.Entry> nowParked;
    try {
      nowParked = parked.list("order.events.v1.dlt", 500);
    } catch (DeadLetters.UnknownTopicException nothingEverParked) {
      nowParked = List.of();
    }
    assertThat(nowParked).noneMatch(entry -> order.toString().equals(entry.key()));
  }

  /** On shutdown the listeners stop first, then the publisher sends what they wrote. */
  @Test
  void thePublisherStopsAfterTheListeners() {
    assertThat(registry.getListenerContainers()).isNotEmpty();
    registry
        .getListenerContainers()
        .forEach(container -> assertThat(publisher.getPhase()).isLessThan(container.getPhase()));
  }

  @Test
  void aPermanentFailureEndsInTheDeadLetterTopicAndThePartitionMovesOn() {
    UUID stuck = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    TestListener.ALWAYS_FAIL.add(stuck);
    tx.executeWithoutResult(s -> outbox.append("OrderCreated", "Order", stuck, payload(stuck)));
    tx.executeWithoutResult(s -> outbox.append("OrderCreated", "Order", next, payload(next)));

    await().atMost(WAIT).until(() -> appliedFor(next).size() == 1);
    assertThat(appliedFor(stuck)).isEmpty();
    assertThat(deadLetters(stuck.toString())).isNotEmpty();
  }

  @Test
  void aPoisonMessageSkipsRetriesAndGoesStraightToTheDeadLetterTopic() throws Exception {
    String marker = "poison-" + UUID.randomUUID();
    int attemptsBefore = TestListener.ATTEMPTS.get();
    double poisonBefore = poisonDetections();
    kafka.send("order.events.v1", marker, "this is not json").get();
    UUID order = UUID.randomUUID();
    tx.executeWithoutResult(s -> outbox.append("OrderCreated", "Order", order, payload(order)));

    await().atMost(WAIT).until(() -> appliedFor(order).size() == 1);
    assertThat(deadLetters(marker))
        .singleElement()
        .satisfies(r -> assertThat(r.value()).isEqualTo("this is not json"));
    // The handler never ran for the poison message: only the good event's attempt counted.
    assertThat(TestListener.ATTEMPTS.get() - attemptsBefore).isEqualTo(1);
    // Detected once: it skipped the retries instead of being parsed and rejected three times.
    assertThat(poisonDetections() - poisonBefore).isEqualTo(1);
  }

  /** Kafka unavailable: writes keep committing, nothing is lost, the backlog drains in order. */
  @Test
  void eventsWrittenWhileKafkaIsDownArePublishedInOrderWhenItReturns() {
    UUID order = UUID.randomUUID();
    var docker = kafkaContainer.getDockerClient();
    docker.pauseContainerCmd(kafkaContainer.getContainerId()).exec();
    List<UUID> written = new ArrayList<>();
    try {
      for (int i = 0; i < 3; i++) {
        written.add(tx.execute(s -> outbox.append("OrderCreated", "Order", order, payload(order))));
      }
      await()
          .during(Duration.ofSeconds(3))
          .atMost(Duration.ofSeconds(5))
          .until(() -> unpublished(order) == 3);
      assertThat(meters.get("outbox.pending").gauge().value()).isGreaterThanOrEqualTo(3);
    } finally {
      docker.unpauseContainerCmd(kafkaContainer.getContainerId()).exec();
    }

    await().atMost(Duration.ofSeconds(60)).until(() -> appliedFor(order).size() == 3);
    assertThat(appliedFor(order)).containsExactlyElementsOf(written);
    assertThat(unpublished(order)).isZero();
  }

  /**
   * While one publisher holds the lock, another instance must not publish: two publishers sending
   * interleaved rows would break per-order ordering.
   */
  @Test
  void aSecondPublisherStandsAsideWhileTheFirstHoldsTheLock() throws Exception {
    UUID order = UUID.randomUUID();
    java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    Thread otherInstance =
        Thread.ofVirtual()
            .start(
                () ->
                    tx.executeWithoutResult(
                        s -> {
                          jdbc.sql(
                                  "select pg_advisory_xact_lock(hashtext('outbox-publisher:order-service'))")
                              .query(Object.class)
                              .single();
                          locked.countDown();
                          try {
                            release.await();
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    try {
      locked.await();
      tx.executeWithoutResult(s -> outbox.append("OrderCreated", "Order", order, payload(order)));
      assertThat(publisher.publishRound().sent()).as("no lock, no publishing").isZero();
      assertThat(unpublished(order)).isEqualTo(1);
    } finally {
      release.countDown();
      otherInstance.join();
    }
    await().atMost(WAIT).until(() -> appliedFor(order).size() == 1);
  }

  // --- Helpers -------------------------------------------------------------------------------

  static Map<String, Object> payload(UUID order) {
    return Map.of(
        "orderId",
        order,
        "customerId",
        UUID.fromString("0192f3c4-1111-7aaa-8bbb-000000000001"),
        "items",
        List.of(Map.of("productId", UUID.randomUUID(), "quantity", 1, "unitPriceMinor", 500)),
        "totalMinor",
        500,
        "currency",
        "USD");
  }

  long outboxRowsFor(UUID order) {
    return jdbc.sql("select count(*) from outbox_events where partition_key = ?")
        .param(order.toString())
        .query(Long.class)
        .single();
  }

  long unpublished(UUID order) {
    return jdbc.sql(
            "select count(*) from outbox_events where partition_key = ? and published_at is null")
        .param(order.toString())
        .query(Long.class)
        .single();
  }

  List<UUID> appliedFor(UUID order) {
    return jdbc.sql("select event_id from applied_events where order_id = ? order by seq")
        .param(order)
        .query(UUID.class)
        .list();
  }

  List<Long> aggregateVersions(UUID order) {
    return jdbc.sql(
            "select (envelope::jsonb ->> 'aggregateVersion')::bigint from outbox_events"
                + " where partition_key = ? order by seq")
        .param(order.toString())
        .query(Long.class)
        .list();
  }

  double poisonDetections() {
    var counter = meters.find("messaging.poison").counter();
    return counter == null ? 0 : counter.count();
  }

  double duplicates() {
    var counter = meters.find("messaging.consumed").tag("outcome", "duplicate").counter();
    return counter == null ? 0 : counter.count();
  }

  /** Reads the dead-letter topic from the start and returns records with the given key. */
  List<ConsumerRecord<String, String>> deadLetters(String key) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of("order.events.v1.dlt"));
      await()
          .atMost(WAIT)
          .until(
              () -> {
                consumer
                    .poll(Duration.ofMillis(500))
                    .forEach(
                        r -> {
                          if (key.equals(r.key())) found.add(r);
                        });
                return !found.isEmpty();
              });
    }
    return found;
  }
}
