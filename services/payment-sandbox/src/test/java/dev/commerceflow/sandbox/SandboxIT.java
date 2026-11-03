package dev.commerceflow.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.commerceflow.testsupport.Containers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"sandbox.api-key=" + SandboxIT.KEY, "sandbox.stall=1500ms"})
@Import(Containers.Postgres.class)
class SandboxIT {

  static final String KEY = "test-sandbox-key-0123456789";

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = JsonMapper.builder().build();

  record Reply(int status, JsonNode body) {}

  @AfterEach
  void resetFault() throws Exception {
    post("/sandbox/faults", null, "{\"mode\":\"NONE\"}");
  }

  @ParameterizedTest
  @CsvSource({
    "tok_success, 200, SUCCEEDED, ",
    "tok_decline, 402, DECLINED, card_declined",
    "tok_insufficient, 402, DECLINED, insufficient_funds"
  })
  void theTokenDecidesTheOutcome(String token, int status, String outcome, String declineCode)
      throws Exception {
    Reply reply = charge(UUID.randomUUID().toString(), token, 1500);
    assertThat(reply.status()).isEqualTo(status);
    assertThat(reply.body().path("status").asString()).isEqualTo(outcome);
    assertThat(reply.body().path("declineCode").asString(null)).isEqualTo(declineCode);
    assertThat(reply.body().path("id").asString()).matches("^ch_[A-Za-z0-9]{8,64}$");
  }

  @Test
  void theSameKeyNeverChargesTwiceEvenWhenSentConcurrently() throws Exception {
    String key = UUID.randomUUID().toString();
    List<Callable<Reply>> calls = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      calls.add(() -> charge(key, "tok_success", 700));
    }
    List<String> ids = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      for (Future<Reply> reply : pool.invokeAll(calls)) {
        assertThat(reply.get().status()).isEqualTo(200);
        ids.add(reply.get().body().path("id").asString());
      }
    }
    assertThat(ids).containsOnly(ids.get(0));
    assertThat(chargesFor(key)).isEqualTo(1);
    // A different amount under the same key still gets the first answer, as with real providers.
    assertThat(charge(key, "tok_success", 999).body().path("amountMinor").asLong()).isEqualTo(700);
  }

  @Test
  void tokTimeoutChargesAndThenHoldsTheResponse() throws Exception {
    String key = UUID.randomUUID().toString();
    long started = System.nanoTime();
    Reply reply = charge(key, "tok_timeout", 500);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofSeconds(1));
    assertThat(reply.status()).isEqualTo(200);
    // A caller that gave up early can still find out what happened.
    JsonNode found = get("/v1/charges?idempotency_key=" + key).body().path("data");
    assertThat(found).hasSize(1);
    assertThat(found.get(0).path("status").asString()).isEqualTo("SUCCEEDED");
  }

  @Test
  void tokUnknownFailsWithoutCharging() throws Exception {
    String key = UUID.randomUUID().toString();
    assertThat(charge(key, "tok_unknown", 500).status()).isEqualTo(503);
    assertThat(get("/v1/charges?idempotency_key=" + key).body().path("data")).isEmpty();
  }

  @Test
  void anUnknownTokenIsRejectedWithoutCharging() throws Exception {
    String key = UUID.randomUUID().toString();
    assertThat(charge(key, "tok_nope", 500).status()).isEqualTo(400);
    assertThat(chargesFor(key)).isZero();
  }

  @Test
  void faultModesOverrideTheTokenUntilReset() throws Exception {
    String charged = UUID.randomUUID().toString();
    String chargeId = charge(charged, "tok_success", 500).body().path("id").asString();
    post("/sandbox/faults", null, "{\"mode\":\"FAIL_ALL\"}");
    String key = UUID.randomUUID().toString();
    assertThat(charge(key, "tok_success", 500).status()).isEqualTo(503);
    assertThat(chargesFor(key)).isZero();
    // Down means down: lookups and refunds fail too, and the refund changes nothing.
    assertThat(get("/v1/charges?idempotency_key=" + charged).status()).isEqualTo(503);
    assertThat(get("/v1/charges/" + chargeId).status()).isEqualTo(503);
    assertThat(
            post(
                    "/v1/refunds",
                    UUID.randomUUID().toString(),
                    "{\"chargeId\":\"" + chargeId + "\",\"amountMinor\":500}")
                .status())
        .isEqualTo(503);

    post("/sandbox/faults", null, "{\"mode\":\"CHARGE_THEN_STALL\"}");
    long started = System.nanoTime();
    assertThat(charge(key, "tok_success", 500).status()).isEqualTo(200);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofSeconds(1));

    post("/sandbox/faults", null, "{\"mode\":\"NONE\"}");
    started = System.nanoTime();
    assertThat(charge(UUID.randomUUID().toString(), "tok_success", 500).status()).isEqualTo(200);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void refundsAreIdempotentAndCannotExceedTheCharge() throws Exception {
    String chargeId =
        charge(UUID.randomUUID().toString(), "tok_success", 800).body().path("id").asString();
    String refundKey = UUID.randomUUID().toString();
    String body = "{\"chargeId\":\"" + chargeId + "\",\"amountMinor\":800}";

    Reply first = post("/v1/refunds", refundKey, body);
    Reply again = post("/v1/refunds", refundKey, body);
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body().path("id").asString()).matches("^re_[A-Za-z0-9]{8,64}$");
    assertThat(again.body().path("id").asString()).isEqualTo(first.body().path("id").asString());
    assertThat(get("/v1/charges/" + chargeId).body().path("refundedMinor").asLong()).isEqualTo(800);

    assertThat(post("/v1/refunds", UUID.randomUUID().toString(), body).status()).isEqualTo(409);
  }

  @Test
  void aDeclinedChargeCannotBeRefunded() throws Exception {
    String chargeId =
        charge(UUID.randomUUID().toString(), "tok_decline", 800).body().path("id").asString();
    String body = "{\"chargeId\":\"" + chargeId + "\",\"amountMinor\":800}";
    assertThat(post("/v1/refunds", UUID.randomUUID().toString(), body).status()).isEqualTo(404);
  }

  @Test
  void everyApiCallNeedsTheKeyButHealthDoesNot() throws Exception {
    HttpRequest.Builder noKey =
        HttpRequest.newBuilder(URI.create(base() + "/v1/charges?idempotency_key=x"));
    assertThat(http.send(noKey.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(401);
    HttpRequest wrongKey = noKey.header("Authorization", "Bearer " + KEY + "x").build();
    assertThat(http.send(wrongKey, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(401);
    HttpRequest health = HttpRequest.newBuilder(URI.create(base() + "/actuator/health")).build();
    assertThat(http.send(health, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
  }

  // --- Helpers -------------------------------------------------------------------------------

  Reply charge(String key, String token, long amountMinor) throws Exception {
    return post(
        "/v1/charges",
        key,
        "{\"amountMinor\":%d,\"currency\":\"EUR\",\"paymentToken\":\"%s\"}"
            .formatted(amountMinor, token));
  }

  Reply post(String path, String idempotencyKey, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base() + path))
            .header("Authorization", "Bearer " + KEY)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return send(request.build());
  }

  Reply get(String path) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .header("Authorization", "Bearer " + KEY)
            .build());
  }

  Reply send(HttpRequest request) throws Exception {
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    String body = response.body();
    return new Reply(response.statusCode(), json.readTree(body.isEmpty() ? "{}" : body));
  }

  long chargesFor(String key) {
    return jdbc.sql("select count(*) from charges where idempotency_key = ?")
        .param(key)
        .query(Long.class)
        .single();
  }

  String base() {
    return "http://localhost:" + port;
  }
}
