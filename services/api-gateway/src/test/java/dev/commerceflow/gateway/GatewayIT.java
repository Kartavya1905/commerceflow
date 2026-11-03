package dev.commerceflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayIT.RedisContainer.class)
class GatewayIT {

  static final FakeBackend BACKEND = start();

  @TestConfiguration(proxyBeanMethods = false)
  static class RedisContainer {
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
      return new GenericContainer<>("redis:8.10.3").withExposedPorts(6379);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("IDENTITY_URI", BACKEND::baseUrl);
    registry.add("CATALOG_URI", BACKEND::baseUrl);
    registry.add("ORDER_URI", BACKEND::baseUrl);
    registry.add("INVENTORY_URI", BACKEND::baseUrl);
    registry.add("PAYMENT_URI", BACKEND::baseUrl);
    registry.add(
        "commerceflow.gateway.jwk-set-uri", () -> BACKEND.baseUrl() + "/.well-known/jwks.json");
    registry.add("MANAGEMENT_PORT", () -> "0");
    // Small auth bucket so the rate-limit test does not need hundreds of requests.
    registry.add("GATEWAY_AUTH_RATE", () -> "1");
    registry.add("GATEWAY_AUTH_BURST", () -> "3");
  }

  @Value("${local.server.port}")
  int port;

  @Value("${local.management.port}")
  int managementPort;

  WebTestClient client;

  @BeforeEach
  void setUp() {
    client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    BACKEND.requests.clear();
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.stop();
  }

  // --- Routing and headers -------------------------------------------------------------------

  @Test
  void publicReadsAreRoutedWithACorrelationIdAndSecurityHeaders() {
    client
        .get()
        .uri("/api/v1/products")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Correlation-Id")
        .expectHeader()
        .valueEquals("X-Content-Type-Options", "nosniff")
        .expectHeader()
        .valueEquals("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        .expectHeader()
        .valueEquals("Referrer-Policy", "no-referrer");

    assertThat(BACKEND.requests)
        .singleElement()
        .satisfies(r -> assertThat(r.correlationId()).isNotBlank());
  }

  @Test
  void securityHeadersAreNotDuplicated() {
    EntityExchangeResult<byte[]> result =
        client
            .get()
            .uri("/api/v1/products")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult();
    assertThat(result.getResponseHeaders().get("X-Content-Type-Options")).hasSize(1);
  }

  @Test
  void correlationIdsArePropagatedWhenSafeAndReplacedWhenNot() {
    client
        .get()
        .uri("/api/v1/products")
        .header("X-Correlation-Id", "client-abc.123")
        .exchange()
        .expectHeader()
        .valueEquals("X-Correlation-Id", "client-abc.123");
    assertThat(BACKEND.requests.peek().correlationId()).isEqualTo("client-abc.123");

    BACKEND.requests.clear();
    String replaced =
        client
            .get()
            .uri("/api/v1/products")
            .header("X-Correlation-Id", "bad id; drop")
            .exchange()
            .returnResult(String.class)
            .getResponseHeaders()
            .getFirst("X-Correlation-Id");
    assertThat(replaced).matches("[0-9a-f-]{36}");
    assertThat(BACKEND.requests.peek().correlationId()).isEqualTo(replaced);
  }

  /** Milestone 4 routes: inventory availability is public, carts and orders need a token. */
  @Test
  void orderAndInventoryRoutesAreWiredWithTheRightAccessRules() {
    client
        .get()
        .uri("/api/v1/inventory/" + java.util.UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isOk();
    client.get().uri("/api/v1/orders").exchange().expectStatus().isUnauthorized();
    client.get().uri("/api/v1/cart").exchange().expectStatus().isUnauthorized();
    client
        .get()
        .uri("/api/v1/admin/inventory/x")
        .header("Authorization", "Bearer " + BACKEND.token("CUSTOMER"))
        .exchange()
        .expectStatus()
        .isForbidden();

    String customer = "Bearer " + BACKEND.token("CUSTOMER");
    client
        .get()
        .uri("/api/v1/orders")
        .header("Authorization", customer)
        .exchange()
        .expectStatus()
        .isOk();
    client
        .get()
        .uri("/api/v1/cart")
        .header("Authorization", customer)
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(BACKEND.sawPath("/api/v1/orders")).isTrue();
    assertThat(BACKEND.sawPath("/api/v1/cart")).isTrue();
    assertThat(BACKEND.sawPath("/api/v1/admin/inventory/x")).isFalse();
  }

  /** Milestone 7: dead-letter listing and replay reach order-service for admins only. */
  @Test
  void deadLetterAdminRouteIsAdminOnly() {
    String path = "/api/v1/admin/dlt/order.events.v1.dlt";
    client.get().uri(path).exchange().expectStatus().isUnauthorized();
    client
        .get()
        .uri(path)
        .header("Authorization", "Bearer " + BACKEND.token("CUSTOMER"))
        .exchange()
        .expectStatus()
        .isForbidden();
    assertThat(BACKEND.sawPath(path)).isFalse();
    client
        .get()
        .uri(path)
        .header("Authorization", "Bearer " + BACKEND.token("ADMIN"))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(BACKEND.sawPath(path)).isTrue();
  }

  /** Milestone 6: payment attempts are visible to admins only, and nothing else of payment is. */
  @Test
  void paymentAdminRouteIsAdminOnly() {
    client.get().uri("/api/v1/admin/payments").exchange().expectStatus().isUnauthorized();
    client
        .get()
        .uri("/api/v1/admin/payments")
        .header("Authorization", "Bearer " + BACKEND.token("CUSTOMER"))
        .exchange()
        .expectStatus()
        .isForbidden();
    assertThat(BACKEND.sawPath("/api/v1/admin/payments")).isFalse();
    client
        .get()
        .uri("/api/v1/admin/payments")
        .header("Authorization", "Bearer " + BACKEND.token("ADMIN"))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(BACKEND.sawPath("/api/v1/admin/payments")).isTrue();
    // A path payment-service does not route through the gateway is simply not there.
    client
        .post()
        .uri("/api/v1/payments")
        .header("Authorization", "Bearer " + BACKEND.token("ADMIN"))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  /** Container health checks and Prometheus use the management port without a token. */
  @Test
  void managementPortServesHealthAndMetricsWithoutAToken() {
    WebTestClient management =
        WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();
    management.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    management.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
  }

  @Test
  void actuatorIsNotServedOnThePublicPort() {
    client.get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();
    client.get().uri("/actuator/health").exchange().expectStatus().isNotFound();
  }

  @Test
  void unknownPathsAreNotRouted() {
    client
        .get()
        .uri("/api/v1/nothing-here")
        .header("Authorization", "Bearer " + BACKEND.token("ADMIN"))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(BACKEND.requests).isEmpty();
  }

  // --- Authentication and authorisation ------------------------------------------------------

  @Test
  void protectedRoutesNeedAValidToken() {
    for (String auth :
        List.of(
            "",
            "Bearer not-a-jwt",
            "Bearer " + BACKEND.expiredToken(),
            "Bearer " + BACKEND.forgedToken())) {
      WebTestClient.RequestHeadersSpec<?> request = client.get().uri("/api/v1/users/me");
      if (!auth.isEmpty()) {
        request = request.header("Authorization", auth);
      }
      request
          .exchange()
          .expectStatus()
          .isUnauthorized()
          .expectHeader()
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .expectBody()
          .jsonPath("$.correlationId")
          .isNotEmpty();
    }
    assertThat(BACKEND.requests).as("no rejected request may reach a service").isEmpty();
  }

  @Test
  void customersCannotReachAdminRoutes() {
    client
        .post()
        .uri("/api/v1/admin/products")
        .header("Authorization", "Bearer " + BACKEND.token("CUSTOMER"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/forbidden");
    assertThat(BACKEND.requests).isEmpty();
  }

  @Test
  void adminsReachAdminRoutesAndTheTokenIsForwarded() {
    String token = BACKEND.token("ADMIN");
    client
        .post()
        .uri("/api/v1/admin/products")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(BACKEND.requests)
        .singleElement()
        .satisfies(r -> assertThat(r.authorization()).isEqualTo("Bearer " + token));
  }

  @Test
  void internalEndpointsAreNeverExposed() {
    client.get().uri("/internal/v1/prices?ids=x").exchange().expectStatus().isUnauthorized();
    client
        .get()
        .uri("/internal/v1/prices?ids=x")
        .header("Authorization", "Bearer " + BACKEND.token("ADMIN"))
        .exchange()
        .expectStatus()
        .isForbidden();
    assertThat(BACKEND.sawPath("/internal/v1/prices")).isFalse();
  }

  // --- Abuse controls ------------------------------------------------------------------------

  @Test
  void loginIsRateLimitedPerClientWithRetryAfter() {
    List<Integer> statuses = new ArrayList<>();
    String retryAfter = null;
    for (int i = 0; i < 8; i++) {
      EntityExchangeResult<byte[]> result =
          client
              .post()
              .uri("/api/v1/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue("{}")
              .exchange()
              .expectBody()
              .returnResult();
      statuses.add(result.getStatus().value());
      if (result.getStatus().value() == 429) {
        retryAfter = result.getResponseHeaders().getFirst("Retry-After");
      }
    }
    assertThat(statuses).contains(200, 429);
    assertThat(retryAfter).isEqualTo("1");
  }

  @Test
  void oversizedBodiesAreRejectedBeforeTheService() {
    byte[] big = new byte[1_100_000];
    client
        .post()
        .uri("/api/v1/admin/products")
        .header("Authorization", "Bearer " + BACKEND.token("ADMIN"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(big)
        .exchange()
        .expectStatus()
        .isEqualTo(413);
    assertThat(BACKEND.requests).isEmpty();
  }

  @Test
  void corsAllowsOnlyTheFrontendOrigin() {
    client
        .options()
        .uri("/api/v1/products")
        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
        .expectHeader()
        .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");

    client
        .options()
        .uri("/api/v1/products")
        .header(HttpHeaders.ORIGIN, "https://evil.example")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  private static FakeBackend start() {
    try {
      return new FakeBackend();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
