package dev.commerceflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.order.saga.SagaOrchestrator;
import dev.commerceflow.order.saga.SagaOrchestrator.Swept;
import dev.commerceflow.order.saga.SagaSweeper;
import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestEvents.Event;
import dev.commerceflow.testsupport.TestTokens;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The deadline sweeper over real Postgres and Kafka. The scheduled sweep is slowed to once an hour
 * so each test runs a round itself, after moving a saga's deadline into the past: what a lost or
 * dead-lettered answer would leave behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  Containers.Postgres.class,
  Containers.Redis.class,
  Containers.Kafka.class,
  TestTokens.class
})
class SagaSweeperIT {

  static final Duration WAIT = Duration.ofSeconds(30);
  static final FakeCatalog CATALOG = new FakeCatalog();
  static final int MAX_RESENDS = 5;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("commerceflow.order.catalog-uri", CATALOG::baseUrl);
    registry.add("commerceflow.order.saga-sweep-interval", () -> "1h");
    registry.add("commerceflow.order.saga-max-resends", () -> MAX_RESENDS);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired SagaSweeper sweeper;
  @Autowired SagaOrchestrator saga;
  @Autowired TransactionTemplate tx;
  final JsonMapper json = JsonMapper.builder().build();

  @Test
  void aLostReservationAnswerIsAskedForAgainWithTheSameRequest() throws Exception {
    UUID product = CATALOG.add("Kettle", 2500);
    UUID order = place(product, 2);
    overdue(order, 0);

    assertThat(saga.sweep(order)).isEqualTo(Swept.RESENT);

    List<JsonNode> created = outbox(order, "OrderCreated");
    assertThat(created).hasSize(2);
    // A new event, but the same request: inventory answers it with the decision it already made.
    assertThat(created.get(1).path("eventId")).isNotEqualTo(created.get(0).path("eventId"));
    assertThat(created.get(1).path("payload")).isEqualTo(created.get(0).path("payload"));
    assertThat(stepAttempts(order)).isEqualTo(1);
    // The next re-send waits a random time up to base * 2^0 = the configured base (10 s).
    assertThat(deadline(order)).isBefore(OffsetDateTime.now().plusSeconds(11));
    assertThat(sagaState(order)).isEqualTo("AWAITING_RESERVATION");
  }

  @Test
  void outOfReSendsInReservationItReleasesThenFails() throws Exception {
    UUID order = place(CATALOG.add("Kettle", 1000), 1);
    overdue(order, MAX_RESENDS);

    assertThat(saga.sweep(order)).isEqualTo(Swept.GAVE_UP);

    assertThat(sagaState(order)).isEqualTo("RELEASING_INVENTORY");
    assertThat(last(order, "ReleaseInventory").path("payload").path("reason").asString())
        .isEqualTo("RESERVATION_TIMEOUT");
    // Still in progress for the customer: it fails only once the stock is back.
    assertThat(statusOf(order)).isEqualTo("PENDING");
    assertThat(stepAttempts(order)).isZero();

    send("inventory.events.v1", inventoryEvent(order, "InventoryReleased"));
    await().atMost(WAIT).until(() -> "FAILED".equals(statusOf(order)));
    assertThat(failureReasonOf(order)).isEqualTo("INTERNAL_ERROR");
  }

  @Test
  void outOfReSendsInPaymentItCancelsThePaymentBeforeReleasing() throws Exception {
    UUID order = place(CATALOG.add("Kettle", 1200), 1);
    send("inventory.events.v1", reserved(order));
    await().atMost(WAIT).until(() -> "AWAITING_PAYMENT".equals(sagaState(order)));

    overdue(order, 2);
    assertThat(saga.sweep(order)).isEqualTo(Swept.RESENT);
    assertThat(outbox(order, "AuthorizePayment")).hasSize(2);
    assertThat(outbox(order, "AuthorizePayment").get(1).path("payload"))
        .isEqualTo(outbox(order, "AuthorizePayment").get(0).path("payload"));

    overdue(order, MAX_RESENDS);
    assertThat(saga.sweep(order)).isEqualTo(Swept.GAVE_UP);
    assertThat(sagaState(order)).isEqualTo("CANCELLING_PAYMENT");
    assertThat(last(order, "CancelPayment").path("payload").path("reason").asString())
        .isEqualTo("SAGA_TIMEOUT");
    // No stock is released while the payment's fate is open.
    assertThat(outbox(order, "ReleaseInventory")).isEmpty();

    send(
        "payment.events.v1",
        paymentEvent(
            order,
            "PaymentFailed",
            Map.of(
                "orderId", order, "paymentAttemptId", UUID.randomUUID(), "reason", "CANCELLED")));
    await().atMost(WAIT).until(() -> "RELEASING_INVENTORY".equals(sagaState(order)));
    send("inventory.events.v1", inventoryEvent(order, "InventoryReleased"));
    await().atMost(WAIT).until(() -> "FAILED".equals(statusOf(order)));
    assertThat(failureReasonOf(order)).isEqualTo("PAYMENT_TIMEOUT");
  }

  /** Reconciliation has nothing to re-send; at its deadline the saga cancels straight away. */
  @Test
  void anUnresolvedReconciliationIsCancelledAtItsDeadline() throws Exception {
    UUID order = place(CATALOG.add("Kettle", 900), 1);
    send("inventory.events.v1", reserved(order));
    await().atMost(WAIT).until(() -> "AWAITING_PAYMENT".equals(sagaState(order)));
    send(
        "payment.events.v1",
        paymentEvent(
            order,
            "PaymentPendingReconciliation",
            Map.of(
                "orderId",
                order,
                "paymentAttemptId",
                UUID.randomUUID(),
                "reason",
                "PROVIDER_TIMEOUT")));
    await().atMost(WAIT).until(() -> "AWAITING_RECONCILIATION".equals(sagaState(order)));

    overdue(order, 0);
    assertThat(saga.sweep(order)).isEqualTo(Swept.GAVE_UP);
    assertThat(sagaState(order)).isEqualTo("CANCELLING_PAYMENT");
    assertThat(outbox(order, "AuthorizePayment")).hasSize(1);
    assertThat(outbox(order, "CancelPayment")).hasSize(1);
  }

  /**
   * Committing has no safe way out: the customer has paid. It keeps being re-sent past the limit,
   * and the saga counts as stuck (the alert), but nothing is undone.
   */
  @Test
  void aCommitWithoutAnswerIsReSentForeverAndReportedStuck() throws Exception {
    UUID order = paidAndCommitting(1100);
    overdue(order, MAX_RESENDS);

    assertThat(saga.sweep(order)).isEqualTo(Swept.STUCK);

    assertThat(sagaState(order)).isEqualTo("AWAITING_COMMIT");
    assertThat(outbox(order, "CommitInventory")).hasSize(2);
    assertThat(outbox(order, "CancelPayment", "ReleaseInventory")).isEmpty();
    assertThat(saga.stuck()).isGreaterThanOrEqualTo(1);

    // The answer finally arrives, and the saga completes as usual.
    send("inventory.events.v1", inventoryEvent(order, "InventoryCommitted"));
    await().atMost(WAIT).until(() -> "CONFIRMED".equals(statusOf(order)));
    assertThat(stepAttempts(order)).isZero();
  }

  /** A re-sent compensating command carries the reason the first one did. */
  @Test
  void aReSentReleaseKeepsItsReason() throws Exception {
    UUID order = place(CATALOG.add("Kettle", 800), 1);
    send("inventory.events.v1", reserved(order));
    await().atMost(WAIT).until(() -> "AWAITING_PAYMENT".equals(sagaState(order)));
    send(
        "payment.events.v1",
        paymentEvent(
            order,
            "PaymentFailed",
            Map.of("orderId", order, "paymentAttemptId", UUID.randomUUID(), "reason", "DECLINED")));
    await().atMost(WAIT).until(() -> "RELEASING_INVENTORY".equals(sagaState(order)));

    overdue(order, 1);
    assertThat(saga.sweep(order)).isEqualTo(Swept.RESENT);

    List<JsonNode> releases = outbox(order, "ReleaseInventory");
    assertThat(releases).hasSize(2);
    assertThat(releases)
        .allSatisfy(
            r ->
                assertThat(r.path("payload").path("reason").asString())
                    .isEqualTo("PAYMENT_FAILED"));
  }

  @Test
  void aSagaThatIsNotOverdueIsLeftAlone() throws Exception {
    UUID order = place(CATALOG.add("Kettle", 700), 1);
    assertThat(saga.sweep(order)).isEqualTo(Swept.SKIPPED);
    assertThat(outbox(order, "OrderCreated")).hasSize(1);
    assertThat(stepAttempts(order)).isZero();
  }

  /**
   * The gate's "sweeper and event handler process the same saga concurrently": both take the row
   * lock, and the sweeper skips a saga someone holds instead of waiting or acting on stale state.
   */
  @Test
  void aSagaLockedByAnEventHandlerIsSkippedNotSweptTwice() throws Exception {
    UUID order = place(CATALOG.add("Kettle", 600), 1);
    overdue(order, 0);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder =
        Thread.ofVirtual()
            .start(
                () ->
                    tx.executeWithoutResult(
                        s -> {
                          jdbc.sql("select 1 from saga_instances where order_id = ? for update")
                              .param(order)
                              .query(Integer.class)
                              .single();
                          locked.countDown();
                          try {
                            release.await(30, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
    try {
      assertThat(saga.sweep(order)).isEqualTo(Swept.SKIPPED);
      assertThat(outbox(order, "OrderCreated")).hasSize(1);
    } finally {
      release.countDown();
      holder.join();
    }
    // Once the lock is gone, the next round sweeps it.
    assertThat(saga.sweep(order)).isEqualTo(Swept.RESENT);
  }

  /** One saga that cannot be swept is rolled back and retried; it does not stop the round. */
  @Test
  void aSagaThatFailsToSweepDoesNotBlockTheOthers() throws Exception {
    UUID broken = zeroTotalAwaitingPayment();
    UUID fine = place(CATALOG.add("Kettle", 500), 1);
    // Both most overdue, so both are in this round's batch.
    overdue(broken, 0, Duration.ofDays(3650));
    overdue(fine, 0, Duration.ofDays(3649));

    Map<Swept, Integer> round = sweeper.sweep();

    assertThat(round.get(Swept.ERROR)).isGreaterThanOrEqualTo(1);
    assertThat(stepAttempts(broken)).isZero();
    assertThat(outbox(broken, "AuthorizePayment")).isEmpty();
    assertThat(stepAttempts(fine)).isEqualTo(1);
    assertThat(outbox(fine, "OrderCreated")).hasSize(2);
    // Out of the way of the other tests.
    jdbc.sql("delete from orders where id = ?").param(broken).update();
  }

  // --- Helpers -------------------------------------------------------------------------------

  UUID paidAndCommitting(long price) throws Exception {
    UUID order = place(CATALOG.add("Kettle", price), 1);
    send("inventory.events.v1", reserved(order));
    await().atMost(WAIT).until(() -> "AWAITING_PAYMENT".equals(sagaState(order)));
    send(
        "payment.events.v1",
        paymentEvent(
            order,
            "PaymentSucceeded",
            Map.of(
                "orderId",
                order,
                "paymentAttemptId",
                UUID.randomUUID(),
                "providerChargeId",
                "ch_abcdefgh12345678",
                "amountMinor",
                price,
                "currency",
                "USD")));
    await().atMost(WAIT).until(() -> "AWAITING_COMMIT".equals(sagaState(order)));
    return order;
  }

  /** The order the API refuses (total 0), whose AuthorizePayment breaks its contract. */
  UUID zeroTotalAwaitingPayment() {
    UUID order = UUID.randomUUID();
    jdbc.sql(
            """
            insert into orders (id, customer_id, status, total_minor, currency, payment_token,
                                idempotency_key, request_hash, created_at, updated_at)
            values (?, ?, 'PAYMENT_PROCESSING', 0, 'USD', 'tok_success', ?, ?, now(), now())
            """)
        .params(order, TestTokens.CUSTOMER_ID, OrderApiIT.key(), new byte[32])
        .update();
    jdbc.sql(
            """
            insert into saga_instances (id, order_id, state, deadline_at, created_at, updated_at)
            values (?, ?, 'AWAITING_PAYMENT', now(), now(), now())
            """)
        .params(UUID.randomUUID(), order)
        .update();
    return order;
  }

  void overdue(UUID order, int stepAttempts) {
    overdue(order, stepAttempts, Duration.ofSeconds(1));
  }

  void overdue(UUID order, int stepAttempts, Duration by) {
    jdbc.sql(
            "update saga_instances set deadline_at = now() - ?::interval, step_attempts = ?"
                + " where order_id = ?")
        .params(by.toSeconds() + " seconds", stepAttempts, order)
        .update();
  }

  List<JsonNode> outbox(UUID order, String... types) {
    return jdbc
        .sql(
            "select envelope from outbox_events where partition_key = ? and event_type in (?)"
                    .replace("(?)", "(" + "?,".repeat(types.length - 1) + "?)")
                + " order by seq")
        .params(OrderMessagingIT.concat(order.toString(), types))
        .query(String.class)
        .list()
        .stream()
        .map(json::readTree)
        .toList();
  }

  JsonNode last(UUID order, String type) {
    List<JsonNode> all = outbox(order, type);
    assertThat(all).as("%s for %s", type, order).isNotEmpty();
    return all.getLast();
  }

  int stepAttempts(UUID order) {
    return jdbc.sql("select step_attempts from saga_instances where order_id = ?")
        .param(order)
        .query(Integer.class)
        .single();
  }

  OffsetDateTime deadline(UUID order) {
    return jdbc.sql("select deadline_at from saga_instances where order_id = ?")
        .param(order)
        .query(OffsetDateTime.class)
        .single();
  }

  String sagaState(UUID order) {
    return jdbc.sql("select state from saga_instances where order_id = ?")
        .param(order)
        .query(String.class)
        .single();
  }

  String statusOf(UUID order) {
    return jdbc.sql("select status from orders where id = ?")
        .param(order)
        .query(String.class)
        .single();
  }

  String failureReasonOf(UUID order) {
    return jdbc.sql("select failure_reason from orders where id = ?")
        .param(order)
        .query(String.class)
        .single();
  }

  UUID place(UUID product, int quantity) throws Exception {
    String body =
        mvc.perform(
                OrderApiIT.place(
                    TestTokens.customer(), OrderApiIT.key(), OrderApiIT.lines(product, quantity)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(body).get("id").asString());
  }

  static Event reserved(UUID order) {
    return OrderMessagingIT.reserved(order);
  }

  static Event inventoryEvent(UUID order, String type) {
    return OrderMessagingIT.inventoryEvent(order, type, Map.of("orderId", order));
  }

  static Event paymentEvent(UUID order, String type, Map<String, ?> payload) {
    return OrderMessagingIT.paymentEvent(order, type, payload);
  }

  void send(String topic, Event event) throws Exception {
    kafka
        .send(topic, json.readTree(event.json()).path("aggregateId").asString(), event.json())
        .get();
  }
}
