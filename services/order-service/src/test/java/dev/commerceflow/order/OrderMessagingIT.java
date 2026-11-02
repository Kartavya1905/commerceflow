package dev.commerceflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestEvents;
import dev.commerceflow.testsupport.TestEvents.Event;
import dev.commerceflow.testsupport.TestTokens;
import dev.commerceflow.testsupport.TopicReader;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * order-service as the saga's orchestrator, over real Kafka and Postgres. The test plays inventory
 * and payment: it sends their events and checks the commands that come back, path by path through
 * ARCHITECTURE.md section E.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  Containers.Postgres.class,
  Containers.Redis.class,
  Containers.Kafka.class,
  TestTokens.class
})
class OrderMessagingIT {

  static final Duration WAIT = Duration.ofSeconds(30);
  static final FakeCatalog CATALOG = new FakeCatalog();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("commerceflow.order.catalog-uri", CATALOG::baseUrl);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired KafkaContainer kafkaContainer;
  @Autowired MeterRegistry meters;
  final JsonMapper json = JsonMapper.builder().build();

  @Test
  void placingAnOrderPublishesOrderCreatedPricedByCatalog() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 1999);
    UUID order = place(lamp, 3);

    List<ConsumerRecord<String, String>> published =
        TopicReader.read(
            kafkaContainer.getBootstrapServers(),
            "order.events.v1",
            r -> order.toString().equals(r.key()),
            1,
            WAIT);
    assertThat(published).hasSize(1);
    JsonNode event = json.readTree(published.get(0).value());
    assertThat(event.path("eventType").asString()).isEqualTo("OrderCreated");
    assertThat(event.path("payload").path("totalMinor").asLong()).isEqualTo(3 * 1999);
    assertThat(event.path("payload").path("items").get(0).path("unitPriceMinor").asLong())
        .isEqualTo(1999);
    assertThat(event.path("aggregateVersion").asLong()).isEqualTo(1);
  }

  @Test
  void aReservationMovesTheOrderToPaymentProcessingOnceEvenIfRedelivered() throws Exception {
    UUID order = place(CATALOG.add("Lamp", 100), 1);
    Event reserved =
        inventoryEvent(
            order,
            "InventoryReserved",
            Map.of(
                "orderId",
                order,
                "reservations",
                List.of(Map.of("productId", UUID.randomUUID(), "quantity", 1))));
    send(reserved);
    send(reserved);

    await().atMost(WAIT).until(() -> "PAYMENT_PROCESSING".equals(statusOf(order)));
    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(4))
        .until(() -> transitionsTo(order, "PAYMENT_PROCESSING") == 1);
    assertThat(causeOf(order, "PAYMENT_PROCESSING")).isEqualTo(reserved.eventId());
  }

  @Test
  void aRejectionFailsTheOrderAndALateReservationCannotReviveIt() throws Exception {
    UUID order = place(CATALOG.add("Lamp", 100), 1);
    send(
        inventoryEvent(
            order,
            "InventoryRejected",
            Map.of(
                "orderId",
                order,
                "reason",
                "OUT_OF_STOCK",
                "productIds",
                List.of(UUID.randomUUID()))));
    await().atMost(WAIT).until(() -> "FAILED".equals(statusOf(order)));

    send(
        inventoryEvent(
            order,
            "InventoryReserved",
            Map.of(
                "orderId",
                order,
                "reservations",
                List.of(Map.of("productId", UUID.randomUUID(), "quantity", 1)))));
    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(4))
        .until(() -> "FAILED".equals(statusOf(order)));
    assertThat(
            jdbc.sql("select failure_reason from orders where id = ?")
                .param(order)
                .query(String.class)
                .single())
        .isEqualTo("OUT_OF_STOCK");
  }

  @Test
  void theHappyPathConfirmsTheOrderAfterPaymentAndCommit() throws Exception {
    UUID order = place(CATALOG.add("Lamp", 1500), 2);
    send(reserved(order));

    JsonNode authorize = awaitCommand("payment.commands.v1", order, "AuthorizePayment");
    assertThat(authorize.path("payload").path("amountMinor").asLong()).isEqualTo(3000);
    assertThat(authorize.path("payload").path("currency").asString()).isEqualTo("USD");
    assertThat(authorize.path("payload").path("paymentToken").asString()).isEqualTo("tok_success");
    assertThat(authorize.path("payload").path("customerId").asString())
        .isEqualTo(TestTokens.CUSTOMER_ID.toString());
    assertThat(statusOf(order)).isEqualTo("PAYMENT_PROCESSING");
    assertThat(sagaState(order)).isEqualTo("AWAITING_PAYMENT");

    UUID attempt = UUID.randomUUID();
    send(succeeded(order, attempt, 3000));
    awaitCommand("inventory.commands.v1", order, "CommitInventory");
    assertThat(sagaState(order)).isEqualTo("AWAITING_COMMIT");
    assertThat(statusOf(order)).isEqualTo("PAYMENT_PROCESSING");

    send(inventoryEvent(order, "InventoryCommitted", Map.of("orderId", order)));
    JsonNode confirmed = awaitCommand("order.events.v1", order, "OrderConfirmed");
    assertThat(confirmed.path("payload").path("paymentAttemptId").asString())
        .isEqualTo(attempt.toString());
    assertThat(confirmed.path("payload").path("totalMinor").asLong()).isEqualTo(3000);
    assertThat(statusOf(order)).isEqualTo("CONFIRMED");
    assertThat(sagaState(order)).isEqualTo("COMPLETED");
    assertThat(hasDeadline(order)).isFalse();
    assertThat(history(order)).containsExactly("PENDING", "PAYMENT_PROCESSING", "CONFIRMED");
    // INV-4 as far as this service can see: confirmed only with the payment and the commit.
    assertThat(commands(order, "ReleaseInventory", "CancelPayment")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"DECLINED", "INSUFFICIENT_FUNDS"})
  void aDeclinedPaymentReleasesTheStockAndOnlyThenFailsTheOrder(String reason) throws Exception {
    UUID order = place(CATALOG.add("Lamp", 900), 1);
    send(reserved(order));
    awaitCommand("payment.commands.v1", order, "AuthorizePayment");

    send(paymentFailed(order, UUID.randomUUID(), reason));
    JsonNode release = awaitCommand("inventory.commands.v1", order, "ReleaseInventory");
    assertThat(release.path("payload").path("reason").asString()).isEqualTo("PAYMENT_FAILED");
    // Still in progress for the customer until the stock is actually back.
    assertThat(statusOf(order)).isEqualTo("PAYMENT_PROCESSING");
    assertThat(sagaState(order)).isEqualTo("RELEASING_INVENTORY");

    send(inventoryEvent(order, "InventoryReleased", Map.of("orderId", order)));
    JsonNode failed = awaitCommand("order.events.v1", order, "OrderFailed");
    assertThat(failed.path("payload").path("reason").asString()).isEqualTo("PAYMENT_DECLINED");
    await().atMost(WAIT).until(() -> "FAILED".equals(statusOf(order)));
    assertThat(failureReasonOf(order)).isEqualTo("PAYMENT_DECLINED");
    assertThat(commands(order, "CommitInventory")).isEmpty();
  }

  @Test
  void aFailedCommitRefundsThePaymentThenReleasesTheStock() throws Exception {
    UUID order = place(CATALOG.add("Lamp", 700), 1);
    send(reserved(order));
    awaitCommand("payment.commands.v1", order, "AuthorizePayment");
    UUID attempt = UUID.randomUUID();
    send(succeeded(order, attempt, 700));
    awaitCommand("inventory.commands.v1", order, "CommitInventory");

    send(
        inventoryEvent(
            order,
            "InventoryCommitFailed",
            Map.of("orderId", order, "reason", "RESERVATION_EXPIRED")));
    JsonNode cancel = awaitCommand("payment.commands.v1", order, "CancelPayment");
    assertThat(cancel.path("payload").path("reason").asString()).isEqualTo("COMMIT_FAILED");
    assertThat(sagaState(order)).isEqualTo("REFUNDING_PAYMENT");
    // No release before the money is back: the order stays paid-for until then.
    assertThat(commands(order, "ReleaseInventory")).isEmpty();

    send(
        paymentEvent(
            order,
            "PaymentRefunded",
            Map.of(
                "orderId",
                order,
                "paymentAttemptId",
                attempt,
                "providerRefundId",
                "re_abcdefgh12345678",
                "amountMinor",
                700,
                "currency",
                "USD")));
    JsonNode release = awaitCommand("inventory.commands.v1", order, "ReleaseInventory");
    assertThat(release.path("payload").path("reason").asString()).isEqualTo("COMMIT_FAILED");

    send(inventoryEvent(order, "InventoryReleased", Map.of("orderId", order)));
    await().atMost(WAIT).until(() -> "FAILED".equals(statusOf(order)));
    assertThat(failureReasonOf(order)).isEqualTo("INTERNAL_ERROR");
    assertThat(sagaState(order)).isEqualTo("FAILED");
    assertThat(history(order)).containsExactly("PENDING", "PAYMENT_PROCESSING", "FAILED");
  }

  /** The gate's "timeout treated as decline": unknown must wait, never release. */
  @Test
  void anUnknownPaymentWaitsForReconciliationAndNeverReleasesTheStock() throws Exception {
    UUID order = place(CATALOG.add("Lamp", 400), 1);
    send(reserved(order));
    awaitCommand("payment.commands.v1", order, "AuthorizePayment");
    UUID attempt = UUID.randomUUID();
    send(
        paymentEvent(
            order,
            "PaymentPendingReconciliation",
            Map.of("orderId", order, "paymentAttemptId", attempt, "reason", "PROVIDER_TIMEOUT")));
    await().atMost(WAIT).until(() -> "AWAITING_RECONCILIATION".equals(sagaState(order)));
    assertThat(statusOf(order)).isEqualTo("PAYMENT_PROCESSING");

    // Reconciliation finds that the charge did go through.
    send(succeeded(order, attempt, 400));
    awaitCommand("inventory.commands.v1", order, "CommitInventory");
    assertThat(commands(order, "ReleaseInventory", "CancelPayment")).isEmpty();
  }

  @Test
  void lateAndDuplicateEventsAreAcknowledgedAndIgnoredNotRetried() throws Exception {
    UUID order = place(CATALOG.add("Lamp", 300), 1);
    Event reserved = reserved(order);
    send(reserved);
    awaitCommand("payment.commands.v1", order, "AuthorizePayment");
    UUID attempt = UUID.randomUUID();
    send(succeeded(order, attempt, 300));
    awaitCommand("inventory.commands.v1", order, "CommitInventory");
    double ignoredBefore = ignoredCount();
    List<Event> late =
        List.of(
            reserved(order), // a re-sent reservation answer, new event id
            succeeded(order, attempt, 300), // payment's answer to a re-sent AuthorizePayment
            paymentFailed(order, attempt, "DECLINED")); // contradicts what already happened
    for (Event event : late) {
      send(event);
    }

    // Acknowledged means committed: each one's dedupe record exists. A thrown event would be
    // rolled back and retried instead, and never get one.
    await().atMost(WAIT).until(() -> late.stream().allMatch(e -> processed(e.eventId())));
    assertThat(ignoredCount()).isGreaterThanOrEqualTo(ignoredBefore + 3);
    assertThat(sagaState(order)).isEqualTo("AWAITING_COMMIT");
    assertThat(commands(order, "AuthorizePayment")).hasSize(1);
    assertThat(commands(order, "CommitInventory")).hasSize(1);
    assertThat(commands(order, "ReleaseInventory")).isEmpty();
    // Acknowledged, so nothing was retried into a dead-letter topic.
    assertThat(
            TopicReader.read(
                kafkaContainer.getBootstrapServers(),
                "payment.events.v1.dlt",
                r -> order.toString().equals(r.key()),
                1,
                Duration.ofSeconds(2)))
        .isEmpty();
  }

  @Test
  void adminsCanListSagasAndSeeWhichAreStuck() throws Exception {
    UUID order = place(CATALOG.add("Lamp", 100), 1);
    mvc.perform(get("/api/v1/admin/sagas").header("Authorization", TestTokens.admin()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[?(@.orderId == '%s')].state", order).value("AWAITING_RESERVATION"));
    // Its deadline is minutes away, so it is not stuck yet.
    mvc.perform(
            get("/api/v1/admin/sagas")
                .param("stuck", "true")
                .header("Authorization", TestTokens.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.orderId == '%s')]", order).isEmpty());
    mvc.perform(get("/api/v1/admin/sagas").header("Authorization", TestTokens.customer()))
        .andExpect(status().isForbidden());
  }

  /**
   * A transition that cannot finish changes nothing: the saga state, the order status and the
   * command commit together or not at all. Provoked with an order whose total (zero, which the API
   * now refuses but the table allows) makes AuthorizePayment break its contract.
   */
  @Test
  void aTransitionThatCannotCompleteLeavesTheSagaAndOrderUntouched() throws Exception {
    UUID order = UUID.randomUUID();
    jdbc.sql(
            """
            insert into orders (id, customer_id, status, total_minor, currency, payment_token,
                                idempotency_key, request_hash, created_at, updated_at)
            values (?, ?, 'PENDING', 0, 'USD', 'tok_success', ?, ?, now(), now())
            """)
        .params(order, TestTokens.CUSTOMER_ID, OrderApiIT.key(), new byte[32])
        .update();
    jdbc.sql(
            """
            insert into saga_instances (id, order_id, state, deadline_at, created_at, updated_at)
            values (?, ?, 'AWAITING_RESERVATION', now() + interval '2 minutes', now(), now())
            """)
        .params(UUID.randomUUID(), order)
        .update();

    send(reserved(order));
    // Every retry fails the same way, so the event ends in the dead-letter topic...
    assertThat(
            TopicReader.read(
                kafkaContainer.getBootstrapServers(),
                "inventory.events.v1.dlt",
                r -> order.toString().equals(r.key()),
                1,
                Duration.ofSeconds(90)))
        .isNotEmpty();
    // ... and none of the attempts left a trace.
    assertThat(sagaState(order)).isEqualTo("AWAITING_RESERVATION");
    assertThat(statusOf(order)).isEqualTo("PENDING");
    assertThat(history(order)).isEmpty();
    assertThat(commands(order, "AuthorizePayment")).isEmpty();
  }

  // --- Helpers -------------------------------------------------------------------------------

  static Event reserved(UUID order) {
    return inventoryEvent(
        order,
        "InventoryReserved",
        Map.of(
            "orderId",
            order,
            "reservations",
            List.of(Map.of("productId", UUID.randomUUID(), "quantity", 1))));
  }

  static Event succeeded(UUID order, UUID attempt, long amountMinor) {
    return paymentEvent(
        order,
        "PaymentSucceeded",
        Map.of(
            "orderId", order,
            "paymentAttemptId", attempt,
            "providerChargeId", "ch_abcdefgh12345678",
            "amountMinor", amountMinor,
            "currency", "USD"));
  }

  static Event paymentFailed(UUID order, UUID attempt, String reason) {
    return paymentEvent(
        order,
        "PaymentFailed",
        Map.of("orderId", order, "paymentAttemptId", attempt, "reason", reason));
  }

  static Event paymentEvent(UUID order, String type, Map<String, ?> payload) {
    return TestEvents.envelope(type, "payment-service", "OrderPayment", order, payload);
  }

  JsonNode awaitCommand(String topic, UUID order, String type) {
    List<ConsumerRecord<String, String>> found =
        TopicReader.read(
            kafkaContainer.getBootstrapServers(),
            topic,
            r -> order.toString().equals(r.key()) && type.equals(typeOf(r.value())),
            1,
            WAIT);
    assertThat(found).as("%s for order %s on %s", type, order, topic).isNotEmpty();
    return json.readTree(found.get(0).value());
  }

  /** The saga's outgoing messages of these types for the order, from the outbox. */
  List<String> commands(UUID order, String... types) {
    return jdbc.sql(
            "select event_type from outbox_events where partition_key = ? and event_type in (?)"
                .replace("(?)", "(" + "?,".repeat(types.length - 1) + "?)"))
        .params(concat(order.toString(), types))
        .query(String.class)
        .list();
  }

  static Object[] concat(String first, String[] rest) {
    Object[] all = new Object[rest.length + 1];
    all[0] = first;
    System.arraycopy(rest, 0, all, 1, rest.length);
    return all;
  }

  String typeOf(String envelope) {
    return json.readTree(envelope).path("eventType").asString();
  }

  String sagaState(UUID order) {
    return jdbc.sql("select state from saga_instances where order_id = ?")
        .param(order)
        .query(String.class)
        .single();
  }

  boolean hasDeadline(UUID order) {
    return jdbc.sql("select deadline_at is not null from saga_instances where order_id = ?")
        .param(order)
        .query(Boolean.class)
        .single();
  }

  String failureReasonOf(UUID order) {
    return jdbc.sql("select failure_reason from orders where id = ?")
        .param(order)
        .query(String.class)
        .single();
  }

  List<String> history(UUID order) {
    return jdbc.sql("select to_status from order_status_history where order_id = ? order by id")
        .param(order)
        .query(String.class)
        .list();
  }

  boolean processed(UUID eventId) {
    return jdbc.sql("select count(*) from processed_events where event_id = ?")
            .param(eventId)
            .query(Long.class)
            .single()
        == 1;
  }

  double ignoredCount() {
    return meters.find("saga.event.ignored").counters().stream().mapToDouble(c -> c.count()).sum();
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

  static Event inventoryEvent(UUID order, String type, Map<String, ?> payload) {
    return TestEvents.envelope(type, "inventory-service", "OrderReservation", order, payload);
  }

  /** To the topic its producer publishes on. */
  void send(Event event) throws Exception {
    JsonNode parsed = json.readTree(event.json());
    String topic =
        "payment-service".equals(parsed.path("producer").asString())
            ? "payment.events.v1"
            : "inventory.events.v1";
    kafka.send(topic, parsed.path("aggregateId").asString(), event.json()).get();
  }

  String statusOf(UUID order) {
    return jdbc.sql("select status from orders where id = ?")
        .param(order)
        .query(String.class)
        .single();
  }

  long transitionsTo(UUID order, String to) {
    return jdbc.sql(
            "select count(*) from order_status_history where order_id = ? and to_status = ?")
        .param(order)
        .param(to)
        .query(Long.class)
        .single();
  }

  UUID causeOf(UUID order, String to) {
    return jdbc.sql(
            "select caused_by_event_id from order_status_history where order_id = ? and to_status = ?")
        .param(order)
        .param(to)
        .query(UUID.class)
        .single();
  }
}
