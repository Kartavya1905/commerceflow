package dev.commerceflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.payment.config.PaymentConfig;
import dev.commerceflow.payment.provider.ProviderClient;
import dev.commerceflow.payment.provider.ProviderClient.ChargeOutcome;
import dev.commerceflow.testsupport.TestEvents;
import dev.commerceflow.testsupport.TestEvents.Event;
import dev.commerceflow.testsupport.TestTokens;
import dev.commerceflow.testsupport.TopicReader;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * payment-service against the real sandbox, Kafka and Postgres. Every test ends by checking what
 * the provider itself recorded, since that, not our own table, is where money moves (INV-3).
 */
class PaymentIT extends PaymentTestBase {

  static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired KafkaContainer kafkaContainer;
  @Autowired ProviderClient provider;
  @Autowired Resilience4JCircuitBreakerFactory breakers;
  @Autowired TransactionTemplate tx;
  final JsonMapper json = JsonMapper.builder().build();

  @Test
  void aSuccessfulChargeIsTakenOnceAndAnnounced() throws Exception {
    UUID order = UUID.randomUUID();
    send(authorize(order, 2599, "tok_success"));

    JsonNode succeeded = awaitEvent(order, "PaymentSucceeded");
    UUID attempt = attemptOf(order);
    assertThat(succeeded.path("payload").path("paymentAttemptId").asString())
        .isEqualTo(attempt.toString());
    assertThat(succeeded.path("payload").path("amountMinor").asLong()).isEqualTo(2599);
    assertThat(succeeded.path("payload").path("providerChargeId").asString())
        .isEqualTo(column(order, "provider_charge_id"));
    assertThat(statusOf(order)).isEqualTo("SUCCEEDED");
    assertThat(SANDBOX.charges(attempt)).isEqualTo(1);
    // The saga's correlation id carries through to the answer.
    assertThat(succeeded.path("correlationId").asString()).startsWith("test-");
  }

  @ParameterizedTest
  @CsvSource({
    "tok_decline, DECLINED, card_declined",
    "tok_insufficient, INSUFFICIENT_FUNDS, insufficient_funds"
  })
  void aDeclineFailsThePaymentWithItsReason(String token, String reason, String code)
      throws Exception {
    UUID order = UUID.randomUUID();
    send(authorize(order, 1000, token));

    JsonNode failed = awaitEvent(order, "PaymentFailed");
    assertThat(failed.path("payload").path("reason").asString()).isEqualTo(reason);
    assertThat(statusOf(order)).isEqualTo("FAILED");
    assertThat(column(order, "failure_code")).isEqualTo(code);
  }

  @Test
  void aDuplicatedAuthorizePaymentChargesOnceAndIsAnsweredEachTime() throws Exception {
    UUID order = UUID.randomUUID();
    Event first = authorize(order, 4200, "tok_success");
    send(first);
    send(authorize(order, 4200, "tok_success")); // a saga re-send: new event id, same order
    send(first); // a plain redelivery of the same event

    List<JsonNode> answers = awaitEvents(order, "PaymentSucceeded", 3);
    assertThat(answers).hasSize(3);
    assertThat(answers)
        .extracting(e -> e.path("payload").path("providerChargeId").asString())
        .containsOnly(column(order, "provider_charge_id"));
    assertThat(attemptCount(order)).isEqualTo(1);
    assertThat(SANDBOX.charges(attemptOf(order))).isEqualTo(1);
  }

  @Test
  void cancelPaymentBeforeAuthorizePaymentMeansNoChargeEver() throws Exception {
    UUID order = UUID.randomUUID();
    send(cancel(order, "SAGA_TIMEOUT"));
    JsonNode cancelled = awaitEvent(order, "PaymentFailed");
    assertThat(cancelled.path("payload").path("reason").asString()).isEqualTo("CANCELLED");

    // The command the saga gave up on finally arrives.
    send(authorize(order, 1000, "tok_success"));
    // It must be answered, or the saga would wait on it, and the answer is CANCELLED.
    List<JsonNode> failures = awaitEvents(order, "PaymentFailed", 2);
    assertThat(failures)
        .extracting(e -> e.path("payload").path("reason").asString())
        .containsExactly("CANCELLED", "CANCELLED");
    assertThat(statusOf(order)).isEqualTo("CANCELLED");
    assertThat(column(order, "amount_minor")).isNull(); // still the tombstone
    assertThat(SANDBOX.charges(attemptOf(order))).isZero();
    assertThat(eventTypes(order)).doesNotContain("PaymentSucceeded");
  }

  @Test
  void aTimeoutIsUnknownNeverFailedAndCancellingItRefundsTheHiddenCharge() throws Exception {
    UUID order = UUID.randomUUID();
    send(authorize(order, 3100, "tok_timeout"));

    JsonNode pending = awaitEvent(order, "PaymentPendingReconciliation");
    assertThat(pending.path("payload").path("reason").asString()).isEqualTo("PROVIDER_TIMEOUT");
    assertThat(statusOf(order)).isEqualTo("UNKNOWN");
    UUID attempt = attemptOf(order);
    // The provider did charge: had this been recorded as a decline, stock would be released for
    // an order that was paid for.
    await().atMost(WAIT).until(() -> SANDBOX.charges(attempt) == 1);
    assertThat(eventTypes(order)).doesNotContain("PaymentFailed");

    send(cancel(order, "SAGA_TIMEOUT"));
    JsonNode refunded = awaitEvent(order, "PaymentRefunded");
    assertThat(refunded.path("payload").path("amountMinor").asLong()).isEqualTo(3100);
    assertThat(statusOf(order)).isEqualTo("REFUNDED");
    assertThat(SANDBOX.refundedMinor(attempt)).isEqualTo(3100);
  }

  @Test
  void anErrorWithoutAChargeIsUnknownAndCancellingItChargesNothing() throws Exception {
    UUID order = UUID.randomUUID();
    send(authorize(order, 900, "tok_unknown"));
    JsonNode pending = awaitEvent(order, "PaymentPendingReconciliation");
    assertThat(pending.path("payload").path("reason").asString()).isEqualTo("PROVIDER_ERROR");

    send(cancel(order, "SAGA_TIMEOUT"));
    JsonNode failed = awaitEvent(order, "PaymentFailed");
    assertThat(failed.path("payload").path("reason").asString()).isEqualTo("CANCELLED");
    assertThat(statusOf(order)).isEqualTo("CANCELLED");
    assertThat(SANDBOX.charges(attemptOf(order))).isZero();
  }

  @Test
  void cancellingAPaidAttemptRefundsItOnceHoweverOftenItIsAsked() throws Exception {
    UUID order = UUID.randomUUID();
    send(authorize(order, 5000, "tok_success"));
    awaitEvent(order, "PaymentSucceeded");

    send(cancel(order, "COMMIT_FAILED"));
    send(cancel(order, "COMMIT_FAILED"));
    List<JsonNode> refunds = awaitEvents(order, "PaymentRefunded", 2);
    assertThat(refunds).hasSize(2);
    assertThat(refunds)
        .extracting(e -> e.path("payload").path("providerRefundId").asString())
        .containsOnly(column(order, "provider_refund_id"));
    assertThat(statusOf(order)).isEqualTo("REFUNDED");
    assertThat(SANDBOX.refundedMinor(attemptOf(order))).isEqualTo(5000);

    // A late AuthorizePayment for a refunded order is answered from the record, not charged.
    send(authorize(order, 5000, "tok_success"));
    assertThat(awaitEvents(order, "PaymentRefunded", 3)).hasSize(3);
    assertThat(SANDBOX.charges(attemptOf(order))).isEqualTo(1);
  }

  @Test
  void theProviderIsNeverCalledInsideADatabaseTransaction() {
    UUID attempt = UUID.randomUUID();
    assertThatThrownBy(
            () -> tx.executeWithoutResult(s -> provider.charge(attempt, 100, "EUR", "tok_success")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inside a database transaction");
    assertThat(SANDBOX.charges(attempt)).isZero();
  }

  @Test
  void adminCanReadAttemptsAndNobodyElseCan() throws Exception {
    UUID order = UUID.randomUUID();
    send(authorize(order, 1234, "tok_success"));
    awaitEvent(order, "PaymentSucceeded");
    UUID attempt = attemptOf(order);

    mvc.perform(
            get("/api/v1/admin/payments")
                .param("orderId", order.toString())
                .header("Authorization", TestTokens.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.items[0].amountMinor").value(1234));
    mvc.perform(
            get("/api/v1/admin/payments/" + attempt).header("Authorization", TestTokens.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value(order.toString()));
    mvc.perform(
            get("/api/v1/admin/payments/" + UUID.randomUUID())
                .header("Authorization", TestTokens.admin()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/admin/payments")
                .param("status", "BOGUS")
                .header("Authorization", TestTokens.admin()))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/admin/payments").header("Authorization", TestTokens.customer()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/payments")).andExpect(status().isUnauthorized());
  }

  /** An open circuit means the provider is not asked at all, and the outcome is UNKNOWN. */
  @Test
  void anOpenCircuitSkipsTheProviderAndLeavesTheChargeUnknown() {
    // Make sure the breaker exists, then open it as a run of failures would.
    provider.charge(UUID.randomUUID(), 100, "EUR", "tok_decline");
    CircuitBreaker breaker =
        breakers.getCircuitBreakerRegistry().find(PaymentConfig.PROVIDER_BREAKER).orElseThrow();
    breaker.transitionToOpenState();
    try {
      UUID attempt = UUID.randomUUID();
      assertThat(provider.charge(attempt, 100, "EUR", "tok_success"))
          .isEqualTo(new ChargeOutcome.Unknown("PROVIDER_ERROR"));
      assertThat(SANDBOX.charges(attempt)).isZero();
    } finally {
      breaker.transitionToClosedState();
    }
  }

  // --- Helpers -------------------------------------------------------------------------------

  static Event authorize(UUID order, long amountMinor, String token) {
    return TestEvents.envelope(
        "AuthorizePayment",
        "order-service",
        "Order",
        order,
        Map.of(
            "orderId", order,
            "customerId", TestTokens.CUSTOMER_ID,
            "amountMinor", amountMinor,
            "currency", "EUR",
            "paymentToken", token));
  }

  static Event cancel(UUID order, String reason) {
    return TestEvents.envelope(
        "CancelPayment",
        "order-service",
        "Order",
        order,
        Map.of("orderId", order, "reason", reason));
  }

  void send(Event event) throws Exception {
    String key = json.readTree(event.json()).path("aggregateId").asString();
    kafka.send("payment.commands.v1", key, event.json()).get();
  }

  JsonNode awaitEvent(UUID order, String type) {
    List<JsonNode> found = awaitEvents(order, type, 1);
    assertThat(found).as("%s for order %s", type, order).isNotEmpty();
    return found.get(0);
  }

  List<JsonNode> awaitEvents(UUID order, String type, int atLeast) {
    return TopicReader.read(
            kafkaContainer.getBootstrapServers(),
            "payment.events.v1",
            r -> order.toString().equals(r.key()) && type.equals(typeOf(r.value())),
            atLeast,
            WAIT)
        .stream()
        .map(r -> json.readTree(r.value()))
        .toList();
  }

  /** Every event type published for the order so far, after a short settling pause. */
  List<String> eventTypes(UUID order) {
    return TopicReader.read(
            kafkaContainer.getBootstrapServers(),
            "payment.events.v1",
            r -> order.toString().equals(r.key()),
            Integer.MAX_VALUE,
            Duration.ofSeconds(3))
        .stream()
        .map(r -> typeOf(r.value()))
        .toList();
  }

  String typeOf(String envelope) {
    return json.readTree(envelope).path("eventType").asString();
  }

  UUID attemptOf(UUID order) {
    return jdbc.sql("select id from payment_attempts where order_id = ?")
        .param(order)
        .query(UUID.class)
        .single();
  }

  long attemptCount(UUID order) {
    return jdbc.sql("select count(*) from payment_attempts where order_id = ?")
        .param(order)
        .query(Long.class)
        .single();
  }

  String statusOf(UUID order) {
    return column(order, "status");
  }

  String column(UUID order, String column) {
    List<String> values =
        jdbc.sql("select " + column + "::text from payment_attempts where order_id = ?")
            .param(order)
            .query(String.class)
            .list();
    return values.isEmpty() ? null : values.get(0);
  }
}
