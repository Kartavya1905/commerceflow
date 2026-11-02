package dev.commerceflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({
  Containers.Postgres.class,
  Containers.Redis.class,
  Containers.Kafka.class,
  TestTokens.class
})
class OrderApiIT {

  static final FakeCatalog CATALOG = new FakeCatalog();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("commerceflow.order.catalog-uri", CATALOG::baseUrl);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  final JsonMapper json = JsonMapper.builder().build();

  // --- Placing orders ------------------------------------------------------------------------

  @Test
  void pricesComeFromCatalogAndTheOrderIsAcceptedAsPending() throws Exception {
    UUID lamp = CATALOG.add("Desk lamp", 1999);
    UUID mug = CATALOG.add("Mug", 4500);
    String customer = TestTokens.customer();

    mvc.perform(place(customer, key(), lines(lamp, 2, mug, 1)))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/orders/")))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.totalMinor").value(2 * 1999 + 4500))
        .andExpect(jsonPath("$.items[?(@.name == 'Desk lamp')].unitPriceMinor").value(1999));

    assertThat(CATALOG.lastAuthorization.get())
        .as("the customer's token is forwarded")
        .isEqualTo(customer);
  }

  @Test
  void aClientCannotSendItsOwnPrice() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 1999);
    String tampered =
        "{\"items\":[{\"productId\":\"%s\",\"quantity\":1,\"unitPriceMinor\":1}],\"paymentToken\":\"tok_success\"}"
            .formatted(lamp);
    String key = key();
    mvc.perform(place(TestTokens.customer(), key, tampered)).andExpect(status().isBadRequest());
    assertThat(ordersWithKey(key)).isZero();
  }

  @Test
  void theSameKeyAndBodyReturnTheSameOrder() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 1000);
    String key = key();
    String first =
        idOf(
            mvc.perform(place(TestTokens.customer(), key, lines(lamp, 1)))
                .andExpect(status().isAccepted())
                .andReturn());
    String replay =
        idOf(
            mvc.perform(place(TestTokens.customer(), key, lines(lamp, 1)))
                .andExpect(status().isOk())
                .andReturn());

    assertThat(replay).isEqualTo(first);
    assertThat(ordersWithKey(key)).isEqualTo(1);
  }

  @Test
  void reorderedOrSplitLinesAreTheSameRequest() throws Exception {
    UUID a = CATALOG.add("A", 100);
    UUID b = CATALOG.add("B", 200);
    String key = key();
    String first =
        idOf(mvc.perform(place(TestTokens.customer(), key, lines(a, 2, b, 1))).andReturn());

    String reordered =
        idOf(
            mvc.perform(place(TestTokens.customer(), key, lines(b, 1, a, 2)))
                .andExpect(status().isOk())
                .andReturn());
    String split =
        idOf(
            mvc.perform(place(TestTokens.customer(), key, lines(a, 1, b, 1, a, 1)))
                .andExpect(status().isOk())
                .andReturn());

    assertThat(reordered).isEqualTo(first);
    assertThat(split).isEqualTo(first);
  }

  @Test
  void reusingAKeyForADifferentOrderIsRejected() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 1000);
    String key = key();
    mvc.perform(place(TestTokens.customer(), key, lines(lamp, 1))).andExpect(status().isAccepted());
    mvc.perform(place(TestTokens.customer(), key, lines(lamp, 5)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
  }

  @Test
  void concurrentDuplicateSubmissionsCreateExactlyOneOrder() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 1000);
    String key = key();
    String customer = TestTokens.customer();
    CountDownLatch start = new CountDownLatch(1);
    List<Future<MvcResult>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 10; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return mvc.perform(place(customer, key, lines(lamp, 1))).andReturn();
                }));
      }
      start.countDown();
      List<String> ids = new ArrayList<>();
      List<Integer> statuses = new ArrayList<>();
      for (Future<MvcResult> f : results) {
        statuses.add(f.get().getResponse().getStatus());
        ids.add(idOf(f.get()));
      }
      assertThat(ids).as("every caller gets the same order").containsOnly(ids.get(0));
      assertThat(statuses).filteredOn(s -> s == 202).hasSize(1);
      assertThat(statuses).filteredOn(s -> s == 200).hasSize(9);
    }
    assertThat(ordersWithKey(key)).isEqualTo(1);
  }

  @Test
  void keysAreScopedPerCustomer() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 1000);
    String key = key();
    String mine =
        idOf(
            mvc.perform(place(TestTokens.customer(), key, lines(lamp, 1)))
                .andExpect(status().isAccepted())
                .andReturn());
    String theirs =
        idOf(
            mvc.perform(
                    place(TestTokens.bearer(UUID.randomUUID(), "CUSTOMER"), key, lines(lamp, 1)))
                .andExpect(status().isAccepted())
                .andReturn());
    assertThat(theirs).isNotEqualTo(mine);
  }

  @Test
  void unknownOrArchivedProductsAreNamed() throws Exception {
    UUID known = CATALOG.add("Known", 100);
    UUID gone = UUID.randomUUID();
    String key = key();
    mvc.perform(place(TestTokens.customer(), key, lines(known, 1, gone, 1)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("/problems/unavailable-products"))
        .andExpect(jsonPath("$.productIds[0]").value(gone.toString()));
    assertThat(ordersWithKey(key)).isZero();
  }

  @Test
  void invalidRequestsAreRejected() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 100);
    String customer = TestTokens.customer();
    mvc.perform(
            post("/api/v1/orders")
                .header("Authorization", customer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(lines(lamp, 1)))
        .andExpect(status().isBadRequest());
    mvc.perform(place(customer, "short", lines(lamp, 1))).andExpect(status().isBadRequest());
    mvc.perform(place(customer, key(), lines(lamp, 0))).andExpect(status().isBadRequest());
    mvc.perform(place(customer, key(), lines(lamp, 600, lamp, 600)))
        .andExpect(status().isBadRequest());
    mvc.perform(place(customer, key(), "{\"items\":[],\"paymentToken\":\"tok_success\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            place(
                customer,
                key(),
                "{\"items\":[{\"productId\":\"%s\",\"quantity\":1}],\"paymentToken\":\"4242424242424242\"}"
                    .formatted(lamp)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void placingAnOrderIsAudited() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 100);
    String id = idOf(mvc.perform(place(TestTokens.customer(), key(), lines(lamp, 1))).andReturn());
    long rows =
        jdbc.sql(
                "select count(*) from audit_events where action = 'ORDER_PLACED' and resource_id = ? and actor_id = ?")
            .param(id)
            .param(TestTokens.CUSTOMER_ID)
            .query(Long.class)
            .single();
    assertThat(rows).isEqualTo(1);
  }

  // --- Reading orders ------------------------------------------------------------------------

  @Test
  void customersSeeOnlyTheirOwnOrders() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 100);
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    String alicesOrder =
        idOf(
            mvc.perform(place(TestTokens.bearer(alice, "CUSTOMER"), key(), lines(lamp, 1)))
                .andReturn());

    mvc.perform(
            get("/api/v1/orders/" + alicesOrder)
                .header("Authorization", TestTokens.bearer(alice, "CUSTOMER")))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/orders/" + alicesOrder)
                .header("Authorization", TestTokens.bearer(bob, "CUSTOMER")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/orders").header("Authorization", TestTokens.bearer(bob, "CUSTOMER")))
        .andExpect(jsonPath("$.items[*].id", not(hasItem(alicesOrder))));
  }

  @Test
  void historyIsNewestFirst() throws Exception {
    UUID lamp = CATALOG.add("Lamp", 100);
    String me = TestTokens.bearer(UUID.randomUUID(), "CUSTOMER");
    String older = idOf(mvc.perform(place(me, key(), lines(lamp, 1))).andReturn());
    String newer = idOf(mvc.perform(place(me, key(), lines(lamp, 2))).andReturn());

    mvc.perform(get("/api/v1/orders").header("Authorization", me))
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.items[0].id").value(newer))
        .andExpect(jsonPath("$.items[1].id").value(older));
  }

  @Test
  void onlyAdminsSeeEveryonesOrders() throws Exception {
    mvc.perform(get("/api/v1/admin/orders").header("Authorization", TestTokens.customer()))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/admin/orders")
                .param("status", "PENDING")
                .header("Authorization", TestTokens.admin()))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/admin/orders")
                .param("status", "nonsense")
                .header("Authorization", TestTokens.admin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void everythingNeedsAToken() throws Exception {
    mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/cart")).andExpect(status().isUnauthorized());
  }

  // --- Cart ----------------------------------------------------------------------------------

  @Test
  void cartsAreKeptPerCustomerAndExpire() throws Exception {
    UUID me = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    String auth = TestTokens.bearer(me, "CUSTOMER");

    mvc.perform(
            put("/api/v1/cart/items/" + product)
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].quantity").value(3));
    mvc.perform(
            get("/api/v1/cart")
                .header("Authorization", TestTokens.bearer(UUID.randomUUID(), "CUSTOMER")))
        .andExpect(jsonPath("$.items.length()").value(0));
    assertThat(redis.getExpire("order:cart:" + me)).as("TTL is set").isPositive();

    mvc.perform(delete("/api/v1/cart/items/" + product).header("Authorization", auth))
        .andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(delete("/api/v1/cart").header("Authorization", auth))
        .andExpect(status().isNoContent());
  }

  @Test
  void cartsAreLimitedTo50Products() throws Exception {
    String auth = TestTokens.bearer(UUID.randomUUID(), "CUSTOMER");
    for (int i = 0; i < 50; i++) {
      mvc.perform(
              put("/api/v1/cart/items/" + UUID.randomUUID())
                  .header("Authorization", auth)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"quantity\":1}"))
          .andExpect(status().isOk());
    }
    mvc.perform(
            put("/api/v1/cart/items/" + UUID.randomUUID())
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/cart-full"));
    mvc.perform(
            put("/api/v1/cart/items/" + UUID.randomUUID())
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":0}"))
        .andExpect(status().isBadRequest());
  }

  // --- Helpers -------------------------------------------------------------------------------

  static String key() {
    return "key-" + UUID.randomUUID();
  }

  /** Alternating productId, quantity pairs. */
  static String lines(Object... pairs) {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < pairs.length; i += 2) {
      if (i > 0) {
        items.append(',');
      }
      items.append("{\"productId\":\"%s\",\"quantity\":%d}".formatted(pairs[i], pairs[i + 1]));
    }
    return "{\"items\":[" + items + "],\"paymentToken\":\"tok_success\"}";
  }

  static MockHttpServletRequestBuilder place(String auth, String key, String body) {
    return post("/api/v1/orders")
        .header("Authorization", auth)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  String idOf(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString()).get("id").asString();
  }

  long ordersWithKey(String key) {
    return jdbc.sql("select count(*) from orders where idempotency_key = ?")
        .param(key)
        .query(Long.class)
        .single();
  }
}
