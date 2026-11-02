package dev.commerceflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.order.config.OrderConfig;
import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestTokens;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Catalog failures must never produce an order with a guessed price, and must not tie up
 * order-service: slow calls time out, repeated failures open the circuit, and the circuit closes
 * again once catalog recovers. Its own Spring context, because it trips the circuit breaker.
 */
@SpringBootTest(properties = "commerceflow.order.breaker-open-for=1s")
@AutoConfigureMockMvc
@Import({Containers.Postgres.class, Containers.Redis.class, TestTokens.class})
class CatalogOutageIT {

  static final FakeCatalog CATALOG = new FakeCatalog();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("commerceflow.order.catalog-uri", CATALOG::baseUrl);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired Resilience4JCircuitBreakerFactory breakers;

  @BeforeEach
  void reset() {
    CATALOG.mode.set(FakeCatalog.Mode.NORMAL);
    // find(), not circuitBreaker(): looking a breaker up by name creates it on the spot with the
    // registry's defaults (open after 100 calls), and Spring Cloud then reuses that instance
    // instead of applying OrderConfig's settings. Only reset one that already exists.
    breakers
        .getCircuitBreakerRegistry()
        .find(OrderConfig.CATALOG_BREAKER)
        .ifPresent(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
  }

  @Test
  void aFailingCatalogMeans503AndNoOrder() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 100);
    CATALOG.mode.set(FakeCatalog.Mode.ERROR);
    String key = OrderApiIT.key();

    mvc.perform(OrderApiIT.place(TestTokens.customer(), key, OrderApiIT.lines(lamp, 1)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value("/problems/price-unavailable"));
    assertThat(orders(key)).isZero();
  }

  @Test
  void aSlowCatalogTimesOutInsteadOfHangingTheRequest() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 100);
    CATALOG.mode.set(FakeCatalog.Mode.SLOW);

    long start = System.nanoTime();
    mvc.perform(
            OrderApiIT.place(TestTokens.customer(), OrderApiIT.key(), OrderApiIT.lines(lamp, 1)))
        .andExpect(status().isServiceUnavailable());
    long millis = (System.nanoTime() - start) / 1_000_000;

    // Read timeout is 1 s; the fake takes 3 s.
    assertThat(millis).isLessThan(2_500);
  }

  @Test
  void repeatedFailuresOpenTheCircuitAndItClosesAfterRecovery() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 100);
    CATALOG.mode.set(FakeCatalog.Mode.ERROR);
    for (int i = 0; i < 5; i++) {
      mvc.perform(
              OrderApiIT.place(TestTokens.customer(), OrderApiIT.key(), OrderApiIT.lines(lamp, 1)))
          .andExpect(status().isServiceUnavailable());
    }

    // Open: fails fast without calling catalog at all.
    int callsBefore = CATALOG.calls.get();
    long start = System.nanoTime();
    mvc.perform(
            OrderApiIT.place(TestTokens.customer(), OrderApiIT.key(), OrderApiIT.lines(lamp, 1)))
        .andExpect(status().isServiceUnavailable());
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
    assertThat(CATALOG.calls.get()).isEqualTo(callsBefore);

    // Catalog recovers; after the open period a trial call goes through and closes the circuit.
    CATALOG.mode.set(FakeCatalog.Mode.NORMAL);
    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                mvc.perform(
                        OrderApiIT.place(
                            TestTokens.customer(), OrderApiIT.key(), OrderApiIT.lines(lamp, 1)))
                    .andExpect(status().isAccepted()));
  }

  /** A retry of an already-placed order must work even while catalog is down. */
  @Test
  void replaysDoNotNeedCatalog() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 100);
    String key = OrderApiIT.key();
    mvc.perform(OrderApiIT.place(TestTokens.customer(), key, OrderApiIT.lines(lamp, 1)))
        .andExpect(status().isAccepted());

    CATALOG.mode.set(FakeCatalog.Mode.ERROR);
    mvc.perform(OrderApiIT.place(TestTokens.customer(), key, OrderApiIT.lines(lamp, 1)))
        .andExpect(status().isOk());
  }

  private long orders(String key) {
    return jdbc.sql("select count(*) from orders where idempotency_key = ?")
        .param(key)
        .query(Long.class)
        .single();
  }
}
