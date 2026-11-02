package dev.commerceflow.inventory;

import static dev.commerceflow.inventory.InventoryFixtures.assertInvariantsHold;
import static dev.commerceflow.inventory.InventoryFixtures.productWithStock;
import static dev.commerceflow.inventory.InventoryFixtures.stockOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.commerceflow.contracts.EventContracts;
import dev.commerceflow.inventory.stock.StockService;
import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestEvents;
import dev.commerceflow.testsupport.TestEvents.Event;
import dev.commerceflow.testsupport.TestTokens;
import dev.commerceflow.testsupport.TopicReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** inventory-service as a Kafka participant: what it consumes and what it answers. */
@SpringBootTest
@Import({Containers.Postgres.class, Containers.Kafka.class, TestTokens.class})
class InventoryMessagingIT {

  static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired KafkaContainer kafkaContainer;
  @Autowired StockService stock;
  @Autowired JdbcClient jdbc;
  final JsonMapper json = JsonMapper.builder().build();
  final EventContracts contracts = EventContracts.load();

  @AfterEach
  void invariantsHold() {
    assertInvariantsHold(jdbc);
  }

  @Test
  void anOrderWithStockIsReservedAndAnsweredWithTheSameCorrelationId() throws Exception {
    UUID product = productWithStock(stock, 5);
    UUID order = UUID.randomUUID();
    Event created = orderCreated(order, product, 2);
    send("order.events.v1", order, created);

    JsonNode reply = replyFor(order, "InventoryReserved");
    assertThat(reply.path("causationId").asString()).isEqualTo(created.eventId().toString());
    assertThat(reply.path("correlationId").asString()).isEqualTo("test-" + created.eventId());
    assertThat(reply.path("payload").path("reservations").get(0).path("quantity").asInt())
        .isEqualTo(2);
    assertThat(stockOf(stock, product).reserved()).isEqualTo(2);
  }

  @Test
  void anOrderWithoutStockIsRejectedNamingTheProduct() throws Exception {
    UUID product = productWithStock(stock, 1);
    UUID order = UUID.randomUUID();
    send("order.events.v1", order, orderCreated(order, product, 3));

    JsonNode reply = replyFor(order, "InventoryRejected");
    assertThat(reply.path("payload").path("reason").asString()).isEqualTo("OUT_OF_STOCK");
    assertThat(reply.path("payload").path("productIds").get(0).asString())
        .isEqualTo(product.toString());
    assertThat(stockOf(stock, product).available()).isEqualTo(1);
  }

  /** At-least-once delivery: the same event twice is applied once and answered once. */
  @Test
  void theSameEventDeliveredTwiceReservesAndAnswersOnce() throws Exception {
    UUID product = productWithStock(stock, 10);
    UUID order = UUID.randomUUID();
    Event created = orderCreated(order, product, 4);
    send("order.events.v1", order, created);
    send("order.events.v1", order, created);

    replyFor(order, "InventoryReserved");
    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(5))
        .until(() -> outboxRows(order, "InventoryReserved") == 1);
    assertThat(stockOf(stock, product).reserved()).isEqualTo(4);
  }

  /**
   * The saga re-sends OrderCreated with a new event id after a timeout: same answer, no new stock.
   */
  @Test
  void aResentRequestGetsTheStoredAnswerAgain() throws Exception {
    UUID product = productWithStock(stock, 10);
    UUID order = UUID.randomUUID();
    send("order.events.v1", order, orderCreated(order, product, 3));
    send("order.events.v1", order, orderCreated(order, product, 3));

    await().atMost(WAIT).until(() -> outboxRows(order, "InventoryReserved") == 2);
    assertThat(stockOf(stock, product).reserved()).isEqualTo(3);
  }

  @Test
  void commitAndReleaseCommandsAreAnswered() throws Exception {
    UUID product = productWithStock(stock, 10);
    UUID committed = UUID.randomUUID();
    send("order.events.v1", committed, orderCreated(committed, product, 2));
    replyFor(committed, "InventoryReserved");
    send(
        "inventory.commands.v1",
        committed,
        command("CommitInventory", committed, Map.of("orderId", committed)));
    replyFor(committed, "InventoryCommitted");

    UUID neverReserved = UUID.randomUUID();
    send(
        "inventory.commands.v1",
        neverReserved,
        command(
            "ReleaseInventory",
            neverReserved,
            Map.of("orderId", neverReserved, "reason", "RESERVATION_TIMEOUT")));
    replyFor(neverReserved, "InventoryReleased");

    assertThat(stockOf(stock, product).committed()).isEqualTo(2);
  }

  /** Everything inventory published in this test run satisfies its contract. */
  @Test
  void everyPublishedEventSatisfiesItsContract() {
    List<String> envelopes =
        jdbc.sql("select envelope from outbox_events").query(String.class).list();
    assertThat(envelopes).isNotEmpty().allSatisfy(e -> assertThat(contracts.validate(e)).isEmpty());
  }

  // --- Helpers -------------------------------------------------------------------------------

  static Event orderCreated(UUID order, UUID product, int quantity) {
    return TestEvents.envelope(
        "OrderCreated",
        "order-service",
        "Order",
        order,
        Map.of(
            "orderId",
            order,
            "customerId",
            TestTokens.CUSTOMER_ID,
            "items",
            List.of(Map.of("productId", product, "quantity", quantity, "unitPriceMinor", 100)),
            "totalMinor",
            100L * quantity,
            "currency",
            "USD"));
  }

  static Event command(String type, UUID order, Map<String, ?> payload) {
    return TestEvents.envelope(type, "order-service", "Order", order, payload);
  }

  void send(String topic, UUID key, Event event) throws Exception {
    kafka.send(topic, key.toString(), event.json()).get();
  }

  JsonNode replyFor(UUID order, String type) {
    List<ConsumerRecord<String, String>> records =
        TopicReader.read(
            kafkaContainer.getBootstrapServers(),
            "inventory.events.v1",
            r ->
                order.toString().equals(r.key())
                    && r.value().contains("\"eventType\":\"" + type + "\""),
            1,
            WAIT);
    assertThat(records).as("%s for order %s", type, order).isNotEmpty();
    return json.readTree(records.get(0).value());
  }

  long outboxRows(UUID order, String type) {
    return jdbc.sql("select count(*) from outbox_events where partition_key = ? and event_type = ?")
        .param(order.toString())
        .param(type)
        .query(Long.class)
        .single();
  }
}
