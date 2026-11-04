package dev.commerceflow.systemtests;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * The Milestone 9 security suite, black-box against the running stack (scripts/dev-up.sh --apps),
 * through the same doors a client uses: the gateway, the web app, and the service ports published
 * on localhost. docs/security/SECURITY.md maps each test to the threat it covers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityIT {

  static final String WEB = "http://localhost:8000";
  static final String GW = Stack.GATEWAY;

  /** Admin endpoints of every service, as the gateway routes them. */
  static final List<String> ADMIN_GETS =
      List.of(
          "/api/v1/admin/orders",
          "/api/v1/admin/sagas",
          "/api/v1/admin/payments",
          "/api/v1/admin/dlt/order.events.v1.dlt",
          "/api/v1/admin/inventory/" + UUID.randomUUID());

  static Stack stack;
  static String admin;
  static Customer alice;
  static Customer bob;
  static UUID product;

  record Customer(String email, String password, String access, String refresh) {}

  @BeforeAll
  static void stack() {
    stack = new Stack();
    stack.awaitHealthy("api-gateway", "identity-service", "order-service");
    admin = stack.adminToken();
    alice = register("alice");
    bob = register("bob");
    product = stack.product(admin, 1200, 50);
  }

  // --- Authorisation -------------------------------------------------------------------------

  @Test
  @Order(1)
  void adminEndpointsRefuseAnonymousAndCustomers() {
    for (String path : ADMIN_GETS) {
      assertThat(stack.get(GW + path, null).status()).as("anonymous %s", path).isEqualTo(401);
      assertThat(stack.get(GW + path, alice.access()).status())
          .as("customer %s", path)
          .isEqualTo(403);
    }
    assertThat(stack.post(GW + "/api/v1/admin/products", alice.access(), "{}").status())
        .isEqualTo(403);
    assertThat(
            stack
                .post(
                    GW + "/api/v1/admin/dlt/order.events.v1.dlt/replay",
                    alice.access(),
                    "{\"partition\":0,\"offset\":0}")
                .status())
        .isEqualTo(403);
  }

  /** Every way of forging an admin token is refused; the honest re-sign proves the tool works. */
  @Test
  @Order(2)
  void forgedTokensAreRefused() throws Exception {
    String real = alice.access();
    String path = GW + "/api/v1/orders";
    // Control: the real token, re-signed by the issuer with nothing changed, still works.
    assertThat(stack.get(path, Jwt.signedByIssuer(stack.root, real, c -> {})).status())
        .isEqualTo(200);

    Map<String, String> forgeries = new java.util.LinkedHashMap<>();
    forgeries.put("role raised, signature kept", Jwt.withClaimsUnsigned(real, raisedToAdmin()));
    forgeries.put("alg none", Jwt.unsigned(real, raisedToAdmin()));
    forgeries.put("signed by another key", Jwt.signedByStranger(real, raisedToAdmin()));
    forgeries.put(
        "expired, signed by the issuer",
        Jwt.signedByIssuer(
            stack.root, real, c -> c.put("exp", Instant.now().minusSeconds(60).getEpochSecond())));
    forgeries.put(
        "another audience, signed by the issuer",
        Jwt.signedByIssuer(stack.root, real, c -> c.put("aud", "someone-else")));
    forgeries.put(
        "another issuer, signed by the issuer's key",
        Jwt.signedByIssuer(stack.root, real, c -> c.put("iss", "https://evil.example")));
    forgeries.forEach(
        (what, token) -> {
          assertThat(stack.get(path, token).status()).as(what).isEqualTo(401);
          assertThat(stack.get(GW + "/api/v1/admin/orders", token).status())
              .as(what)
              .isIn(401, 403);
        });
  }

  /** Bypassing the gateway gains nothing: each service checks the token itself. */
  @Test
  @Order(3)
  void servicesCheckTokensThemselvesBehindTheGateway() {
    assertThat(stack.get(Stack.ORDER + "/api/v1/orders", null).status()).isEqualTo(401);
    assertThat(stack.get(Stack.ORDER + "/api/v1/admin/sagas", alice.access()).status())
        .isEqualTo(403);
    assertThat(stack.get(Stack.PAYMENT + "/api/v1/admin/payments", null).status()).isEqualTo(401);
    assertThat(stack.get(Stack.PAYMENT + "/api/v1/admin/payments", alice.access()).status())
        .isEqualTo(403);
    assertThat(
            stack
                .get("http://localhost:8084/api/v1/admin/inventory/" + product, alice.access())
                .status())
        .isEqualTo(403);
  }

  /** INV-7: another customer's order is "not found", never "forbidden". */
  @Test
  @Order(4)
  void anotherCustomersOrderDoesNotExistForYou() {
    UUID order = stack.placeOrder(alice.access(), product, 1, "tok_success");
    assertThat(stack.get(GW + "/api/v1/orders/" + order, alice.access()).status()).isEqualTo(200);
    assertThat(stack.get(GW + "/api/v1/orders/" + order, bob.access()).status()).isEqualTo(404);
    assertThat(stack.get(GW + "/api/v1/orders/" + UUID.randomUUID(), bob.access()).status())
        .isEqualTo(404);
    JsonNode bobs = stack.get(GW + "/api/v1/orders?size=100", bob.access()).body();
    assertThat(bobs.path("items").toString()).doesNotContain(order.toString());
    // A cart belongs to the token, not to an id in the request: Bob cannot reach Alice's.
    stack.call(
        "PUT", GW + "/api/v1/cart/items/" + product, alice.access(), "{\"quantity\":2}", Map.of());
    assertThat(stack.get(GW + "/api/v1/cart", bob.access()).body().path("items").size()).isZero();
  }

  // --- Replays -------------------------------------------------------------------------------

  /** The same key and body is the same order; the same key with another body is refused. */
  @Test
  @Order(5)
  void anIdempotencyKeyCannotBeReplayedWithADifferentRequest() {
    String key = "security-" + UUID.randomUUID();
    String body = order(1, "tok_success");
    Stack.Reply first = placeWithKey(key, body, alice.access());
    Stack.Reply again = placeWithKey(key, body, alice.access());
    assertThat(first.status()).isEqualTo(202);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("id").asString()).isEqualTo(first.body().path("id").asString());

    Stack.Reply changed = placeWithKey(key, order(5, "tok_success"), alice.access());
    assertThat(changed.status()).isEqualTo(422);
    assertThat(changed.body().path("type").asString()).endsWith("idempotency-key-reused");
    // Bob using Alice's key is just a new order of his own: keys are per customer.
    assertThat(placeWithKey(key, body, bob.access()).status()).isEqualTo(202);
  }

  /** A refresh token works once; replaying it revokes the session it belonged to. */
  @Test
  @Order(6)
  void aReplayedRefreshTokenRevokesTheSession() {
    Customer carol = register("carol");
    Stack.Reply rotated = refresh(carol.refresh());
    assertThat(rotated.status()).isEqualTo(200);
    String next = rotated.body().path("refreshToken").asString();

    assertThat(refresh(carol.refresh()).status()).as("the old token, replayed").isEqualTo(401);
    assertThat(refresh(next).status()).as("its successor, after the replay").isEqualTo(401);
  }

  // --- Input ---------------------------------------------------------------------------------

  @Test
  @Order(7)
  void invalidInputIsRefusedWithAProblemAndNoInternals() {
    List<Stack.Reply> replies = new ArrayList<>();
    replies.add(stack.post(GW + "/api/v1/auth/register", null, "{not json"));
    replies.add(
        stack.post(
            GW + "/api/v1/auth/register",
            null,
            "{\"email\":\"x@example.com\",\"password\":\"long-enough-password\",\"displayName\":\""
                + "x".repeat(10_000)
                + "\"}"));
    replies.add(
        stack.post(
            GW + "/api/v1/auth/register",
            null,
            "{\"email\":\"' OR 1=1 --\",\"password\":\"long-enough-password\",\"displayName\":\"x\"}"));
    // A client may not set a price: unknown fields are refused, not ignored.
    replies.add(
        stack.call(
            "POST",
            GW + "/api/v1/orders",
            alice.access(),
            "{\"items\":[{\"productId\":\""
                + product
                + "\",\"quantity\":1,\"unitPriceMinor\":1}],\"paymentToken\":\"tok_success\"}",
            Map.of("Idempotency-Key", "security-" + UUID.randomUUID())));
    replies.add(
        stack.call(
            "PUT",
            GW + "/api/v1/cart/items/not-a-uuid",
            alice.access(),
            "{\"quantity\":1}",
            Map.of()));
    for (Stack.Reply reply : replies) {
      assertThat(reply.status()).as(reply.body().toString()).isEqualTo(400);
      String text = reply.body().toString();
      assertThat(text)
          .doesNotContainIgnoringCase("exception")
          .doesNotContain("at dev.commerceflow", "SQL");
    }
  }

  /** Injection-shaped input stays data: the search still works and finds nothing odd. */
  @Test
  @Order(8)
  void injectionStringsAreJustText() {
    for (String q :
        List.of("' OR '1'='1", "'; DROP TABLE products; --", "<script>alert(1)</script>")) {
      Stack.Reply reply =
          stack.get(
              GW
                  + "/api/v1/products?q="
                  + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8),
              null);
      assertThat(reply.status()).as(q).isEqualTo(200);
      assertThat(reply.body().path("items").size()).as(q).isZero();
    }
    // And the catalogue is still there.
    assertThat(stack.get(GW + "/api/v1/products/" + product, null).status()).isEqualTo(200);
  }

  @Test
  @Order(9)
  void theWebAppSendsItsSecurityHeaders() throws Exception {
    var response =
        stack.http.send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create(WEB + "/orders/x")).build(),
            java.net.http.HttpResponse.BodyHandlers.discarding());
    var headers = response.headers();
    assertThat(headers.firstValue("content-security-policy").orElse(""))
        .contains("default-src 'self'", "frame-ancestors 'none'")
        .doesNotContain("unsafe-inline");
    assertThat(headers.firstValue("x-content-type-options")).contains("nosniff");
    assertThat(headers.firstValue("referrer-policy")).contains("no-referrer");
    assertThat(headers.firstValue("server").orElse("")).doesNotMatch(".*\\d.*");
  }

  // --- What the system leaves behind ---------------------------------------------------------

  /** INV-8 and the review gate: no personal data or token in any service's logs. */
  @Test
  @Order(10)
  void theLogsHoldNoEmailsOrTokens() {
    List<String> found = new ArrayList<>();
    Pattern email =
        Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    Pattern jwt = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.");
    Pattern token = Pattern.compile("tok_(success|decline|insufficient|timeout|unknown)");
    for (String service :
        List.of(
            "identity-service",
            "catalog-service",
            "order-service",
            "inventory-service",
            "payment-service",
            "api-gateway")) {
      String logs = stack.docker("logs", "--since", "2h", Stack.container(service));
      for (Pattern p : List.of(email, jwt, token)) {
        Matcher m = p.matcher(logs);
        if (m.find()) {
          found.add(service + ": " + p.pattern().substring(0, 12) + "… at offset " + m.start());
        }
      }
      assertThat(logs).as("%s logs anything", service).isNotBlank();
    }
    assertThat(found).as("personal data or tokens in logs").isEmpty();
  }

  /** The review gate: metric labels come from bounded sets, never ids or emails. */
  @Test
  @Order(11)
  void metricLabelsHoldNoIdsOrEmails() throws Exception {
    Pattern uuid = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    Pattern email = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    for (int port : List.of(8081, 8082, 8083, 8084, 8085)) {
      String text =
          stack
              .http
              .send(
                  java.net.http.HttpRequest.newBuilder(
                          java.net.URI.create("http://localhost:" + port + "/actuator/prometheus"))
                      .build(),
                  java.net.http.HttpResponse.BodyHandlers.ofString())
              .body();
      // Distinct label combinations per metric, not raw series: a histogram's buckets (le) are
      // one combination each, and what can grow without bound is the combinations.
      Map<String, java.util.Set<String>> combinations = new java.util.HashMap<>();
      for (String line : text.split("\n")) {
        if (line.startsWith("#") || line.isBlank()) {
          continue;
        }
        int brace = line.indexOf('{');
        String labels = brace < 0 ? "" : line.substring(brace, line.lastIndexOf('}') + 1);
        assertThat(uuid.matcher(labels).find()).as(":%d %s", port, line).isFalse();
        assertThat(email.matcher(labels).find()).as(":%d %s", port, line).isFalse();
        String metric =
            (brace < 0 ? line.split(" ")[0] : line.substring(0, brace))
                .replaceAll("_(bucket|count|sum|max)$", "");
        combinations
            .computeIfAbsent(metric, m -> new java.util.HashSet<>())
            .add(labels.replaceAll(",?le=\"[^\"]*\"", ""));
      }
      combinations.forEach(
          (metric, sets) ->
              assertThat(sets.size())
                  .as(":%d %s label combinations", port, metric)
                  .isLessThan(100));
    }
  }

  /** Last: it uses up this client's login allowance for a few seconds. */
  @Test
  @Order(12)
  void guessingPasswordsIsSlowedDownByTheGateway() {
    int limited = 0;
    for (int i = 0; i < 30; i++) {
      int status =
          stack
              .post(
                  GW + "/api/v1/auth/login",
                  null,
                  "{\"email\":\"" + alice.email() + "\",\"password\":\"wrong-" + i + "\"}")
              .status();
      assertThat(status).isIn(401, 429);
      if (status == 429) {
        limited++;
      }
    }
    assertThat(limited).as("requests refused with 429").isPositive();
  }

  // --- Helpers -------------------------------------------------------------------------------

  static java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> raisedToAdmin() {
    return c -> {
      ArrayNode roles = c.putArray("roles");
      roles.add("ADMIN");
    };
  }

  static Customer register(String name) {
    String email = "security-" + name + "-" + UUID.randomUUID() + "@example.com";
    String password = "security-" + UUID.randomUUID();
    assertThat(
            stack
                .post(
                    GW + "/api/v1/auth/register",
                    null,
                    "{\"email\":\""
                        + email
                        + "\",\"password\":\""
                        + password
                        + "\",\"displayName\":\""
                        + name
                        + "\"}")
                .status())
        .isEqualTo(201);
    Stack.Reply login =
        stack.call(
            "POST",
            GW + "/api/v1/auth/login",
            null,
            "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
            Map.of("X-Token-Delivery", "body"));
    assertThat(login.status()).isEqualTo(200);
    return new Customer(
        email,
        password,
        login.body().path("accessToken").asString(),
        login.body().path("refreshToken").asString());
  }

  static Stack.Reply refresh(String refreshToken) {
    return stack.call(
        "POST",
        GW + "/api/v1/auth/refresh",
        null,
        "{\"refreshToken\":\"" + refreshToken + "\"}",
        Map.of("X-Token-Delivery", "body"));
  }

  static String order(int quantity, String paymentToken) {
    return "{\"items\":[{\"productId\":\""
        + product
        + "\",\"quantity\":"
        + quantity
        + "}],\"paymentToken\":\""
        + paymentToken
        + "\"}";
  }

  static Stack.Reply placeWithKey(String key, String body, String token) {
    return stack.call("POST", GW + "/api/v1/orders", token, body, Map.of("Idempotency-Key", key));
  }
}
