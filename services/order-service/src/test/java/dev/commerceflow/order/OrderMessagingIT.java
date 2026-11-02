package dev.commerceflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** order-service as a Kafka participant: OrderCreated out, inventory's answers in. */
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

  // --- Helpers -------------------------------------------------------------------------------

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

  void send(Event event) throws Exception {
    JsonNode parsed = json.readTree(event.json());
    kafka.send("inventory.events.v1", parsed.path("aggregateId").asString(), event.json()).get();
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
