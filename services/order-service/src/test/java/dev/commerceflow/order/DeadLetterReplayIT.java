package dev.commerceflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestEvents.Event;
import dev.commerceflow.testsupport.TestTokens;
import dev.commerceflow.testsupport.TopicReader;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listing and replaying dead letters. A record is parked for real (its transition breaks a contract
 * on every attempt), the cause is fixed, and the replay goes through the normal consumer: replaying
 * it twice applies it once (the gate's "replay of a DLT message bypasses dedupe").
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  Containers.Postgres.class,
  Containers.Redis.class,
  Containers.Kafka.class,
  TestTokens.class
})
class DeadLetterReplayIT {

  static final String DLT = "inventory.events.v1.dlt";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("commerceflow.order.catalog-uri", () -> "http://localhost:1");
    registry.add("commerceflow.order.saga-sweep-interval", () -> "1h");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired KafkaContainer kafkaContainer;
  @Autowired MeterRegistry meters;
  final JsonMapper json = JsonMapper.builder().build();

  @Test
  void aParkedEventIsListedAndReplayedOnceTheCauseIsFixedAndASecondReplayChangesNothing()
      throws Exception {
    UUID order = zeroTotalAwaitingReservation();
    Event reserved = OrderMessagingIT.reserved(order);
    kafka.send("inventory.events.v1", order.toString(), reserved.json()).get();
    ConsumerRecord<String, String> parked = awaitParked(order);

    JsonNode listed =
        json.readTree(
            mvc.perform(
                    get("/api/v1/admin/dlt/" + DLT)
                        .param("limit", "500")
                        .header("Authorization", TestTokens.admin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    JsonNode entry = null;
    for (JsonNode item : listed.path("items")) {
      if (order.toString().equals(item.path("key").asString())) {
        entry = item;
      }
    }
    assertThat(entry).as("listed entry for %s", order).isNotNull();
    assertThat(entry.path("eventId").asString()).isEqualTo(reserved.eventId().toString());
    assertThat(entry.path("eventType").asString()).isEqualTo("InventoryReserved");
    assertThat(entry.path("originalTopic").asString()).isEqualTo("inventory.events.v1");
    assertThat(entry.path("failure").asString()).contains("violates its contract");

    // The cause is fixed: the order now has a chargeable total.
    jdbc.sql("update orders set total_minor = 4200 where id = ?").param(order).update();

    double duplicatesBefore = duplicates();
    String body = "{\"partition\":%d,\"offset\":%d}".formatted(parked.partition(), parked.offset());
    for (int i = 0; i < 2; i++) {
      mvc.perform(
              post("/api/v1/admin/dlt/" + DLT + "/replay")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .header("Authorization", TestTokens.admin()))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.toTopic").value("inventory.events.v1"))
          .andExpect(jsonPath("$.eventId").value(reserved.eventId().toString()));
    }

    await().atMost(Duration.ofSeconds(30)).until(() -> "AWAITING_PAYMENT".equals(sagaState(order)));
    // Both replays were consumed, and the second met the dedupe record: only one applied.
    await().atMost(Duration.ofSeconds(30)).until(() -> duplicates() >= duplicatesBefore + 1);
    assertThat(outboxCount(order, "AuthorizePayment")).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "select count(*) from audit_events where action = 'DLT_REPLAYED'"
                        + " and resource_id = ?")
                .param(DLT + "/" + parked.partition() + "@" + parked.offset())
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void onlyDeadLetterTopicsCanBeReadOrReplayed() throws Exception {
    mvc.perform(
            get("/api/v1/admin/dlt/inventory.events.v1")
                .header("Authorization", TestTokens.admin()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/admin/dlt/order.events.v1/replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"partition\":0,\"offset\":0}")
                .header("Authorization", TestTokens.admin()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/admin/dlt/" + DLT + "/replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"partition\":0,\"offset\":999999}")
                .header("Authorization", TestTokens.admin()))
        .andExpect(status().isNotFound());
  }

  @Test
  void customersCannotSeeOrReplayDeadLetters() throws Exception {
    mvc.perform(get("/api/v1/admin/dlt/" + DLT).header("Authorization", TestTokens.customer()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/dlt/" + DLT + "/replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"partition\":0,\"offset\":0}")
                .header("Authorization", TestTokens.customer()))
        .andExpect(status().isForbidden());
  }

  ConsumerRecord<String, String> awaitParked(UUID order) {
    List<ConsumerRecord<String, String>> found =
        TopicReader.read(
            kafkaContainer.getBootstrapServers(),
            DLT,
            r -> order.toString().equals(r.key()),
            1,
            Duration.ofSeconds(90));
    assertThat(found).isNotEmpty();
    return found.get(0);
  }

  UUID zeroTotalAwaitingReservation() {
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
    return order;
  }

  /** Deliveries of an inventory event the consumer recognised as already applied. */
  double duplicates() {
    return meters
        .find("messaging.consumed")
        .tag("eventType", "InventoryReserved")
        .tag("outcome", "duplicate")
        .counters()
        .stream()
        .mapToDouble(c -> c.count())
        .sum();
  }

  String sagaState(UUID order) {
    return jdbc.sql("select state from saga_instances where order_id = ?")
        .param(order)
        .query(String.class)
        .single();
  }

  long outboxCount(UUID order, String type) {
    return jdbc.sql("select count(*) from outbox_events where partition_key = ? and event_type = ?")
        .params(order.toString(), type)
        .query(Long.class)
        .single();
  }
}
