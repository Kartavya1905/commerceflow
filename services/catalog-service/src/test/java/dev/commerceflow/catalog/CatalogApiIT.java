package dev.commerceflow.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestTokens;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({Containers.Postgres.class, Containers.Redis.class, TestTokens.class})
class CatalogApiIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  final JsonMapper json = JsonMapper.builder().build();

  // --- Authorisation -------------------------------------------------------------------------

  @Test
  void onlyAdminsManageProducts() throws Exception {
    String body = productJson(sku(), "Desk lamp", "lighting", 2599);
    mvc.perform(
            post("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/admin/products")
                .header("Authorization", TestTokens.customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Content-Type", "application/problem+json"));
    mvc.perform(
            post("/api/v1/admin/products")
                .header("Authorization", TestTokens.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"));
  }

  @Test
  void internalPricesNeedATokenButNoParticularRole() throws Exception {
    UUID id = create(sku(), "Price check item", "misc", 1234);
    mvc.perform(get("/internal/v1/prices").param("ids", id.toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/internal/v1/prices")
                .param("ids", id.toString())
                .header("Authorization", TestTokens.customer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].priceMinor").value(1234));
  }

  // --- Validation ----------------------------------------------------------------------------

  @Test
  void rejectsInvalidProducts() throws Exception {
    String invalid =
        "{\"sku\":\"lower case\",\"name\":\"\",\"category\":\"x\",\"priceMinor\":-5,\"currency\":\"usd\"}";
    mvc.perform(
            post("/api/v1/admin/products")
                .header("Authorization", TestTokens.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
  }

  @Test
  void duplicateSkuIsAConflict() throws Exception {
    String sku = sku();
    create(sku, "First", "misc", 100);
    mvc.perform(
            post("/api/v1/admin/products")
                .header("Authorization", TestTokens.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson(sku, "Second", "misc", 200)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/sku-taken"));
  }

  @Test
  void pagingLimitsAreEnforced() throws Exception {
    mvc.perform(get("/api/v1/products").param("size", "1000")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/products").param("page", "-1")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/products").param("sort", "price; drop table products"))
        .andExpect(status().isBadRequest());
  }

  // --- Search --------------------------------------------------------------------------------

  @Test
  void searchRanksNameMatchesAboveDescriptionMatches() throws Exception {
    String word = "zyx" + UUID.randomUUID().toString().substring(0, 8);
    UUID inDescription = create(sku(), "Plain item", "misc", 100, "mentions " + word + " here");
    UUID inName = create(sku(), word + " deluxe", "misc", 100, "");

    JsonNode page = getJson(get("/api/v1/products").param("q", word));
    assertThat(page.get("totalItems").asLong()).isEqualTo(2);
    assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(inName.toString());
    assertThat(page.get("items").get(1).get("id").asString()).isEqualTo(inDescription.toString());
  }

  @Test
  void filtersByCategory() throws Exception {
    String category = "cat-" + UUID.randomUUID().toString().substring(0, 8);
    create(sku(), "In category", category, 100);
    create(sku(), "Elsewhere", "other", 100);

    mvc.perform(get("/api/v1/products").param("category", category))
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].name").value("In category"));
  }

  @Test
  void hostileSearchTextIsJustText() throws Exception {
    mvc.perform(get("/api/v1/products").param("q", "'; drop table products; -- \" or 1=1"))
        .andExpect(status().isOk());
    assertThat(jdbc.sql("select count(*) from products").query(Long.class).single()).isPositive();
  }

  // --- Updates, versions, caching ------------------------------------------------------------

  @Test
  void staleVersionIsRejectedAndCurrentVersionWins() throws Exception {
    String sku = sku();
    UUID id = create(sku, "Versioned", "misc", 500);

    mvc.perform(update(id, sku, 600, 0))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(update(id, sku, 700, 0))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/stale-version"));
    mvc.perform(get("/api/v1/products/" + id)).andExpect(jsonPath("$.priceMinor").value(600));
  }

  @Test
  void readsAreCachedAndAdminEditsAreVisibleImmediately() throws Exception {
    String sku = sku();
    UUID id = create(sku, "Cached", "misc", 1000);

    mvc.perform(get("/api/v1/products/" + id)).andExpect(jsonPath("$.priceMinor").value(1000));
    Set<String> keys = redis.keys("catalog:product::" + id);
    assertThat(keys).as("product read should be cached").hasSize(1);

    mvc.perform(update(id, sku, 1500, 0)).andExpect(status().isOk());
    assertThat(redis.keys("catalog:product::" + id)).as("update evicts after commit").isEmpty();
    mvc.perform(get("/api/v1/products/" + id)).andExpect(jsonPath("$.priceMinor").value(1500));
  }

  @Test
  void aFailedEditDoesNotEvictTheCache() throws Exception {
    String sku = sku();
    UUID id = create(sku, "Kept", "misc", 1000);
    mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk());

    mvc.perform(update(id, sku, 9999, 42)).andExpect(status().isConflict());
    assertThat(redis.keys("catalog:product::" + id)).hasSize(1);
  }

  @Test
  void archivedProductsDisappearFromEveryPublicView() throws Exception {
    UUID id = create(sku(), "Soon gone", "archive-test", 100);
    mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk());

    mvc.perform(
            post("/api/v1/admin/products/" + id + "/archive")
                .header("Authorization", TestTokens.admin()))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/products").param("category", "archive-test"))
        .andExpect(jsonPath("$.items[*].id", not(hasItem(id.toString()))));
    mvc.perform(
            get("/internal/v1/prices")
                .param("ids", id.toString())
                .header("Authorization", TestTokens.customer()))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void adminActionsAreAuditedWithTheActor() throws Exception {
    UUID id = create(sku(), "Audited", "misc", 100);
    Long rows =
        jdbc.sql(
                "select count(*) from audit_events where resource_id = ? and actor_id = ? and action = 'PRODUCT_CREATED'")
            .param(id.toString())
            .param(TestTokens.ADMIN_ID)
            .query(Long.class)
            .single();
    assertThat(rows).isEqualTo(1);
  }

  // --- Helpers -------------------------------------------------------------------------------

  static String sku() {
    return "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  static String productJson(String sku, String name, String category, long price) {
    return productJson(sku, name, category, price, "");
  }

  static String productJson(
      String sku, String name, String category, long price, String description) {
    return """
        {"sku":"%s","name":"%s","description":"%s","category":"%s","priceMinor":%d,"currency":"USD"}"""
        .formatted(sku, name, description, category, price);
  }

  UUID create(String sku, String name, String category, long price) throws Exception {
    return create(sku, name, category, price, "");
  }

  UUID create(String sku, String name, String category, long price, String description)
      throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/admin/products")
                    .header("Authorization", TestTokens.admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(productJson(sku, name, category, price, description)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asString());
  }

  static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder update(
      UUID id, String sku, long price, long version) {
    return put("/api/v1/admin/products/" + id)
        .header("Authorization", TestTokens.admin())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"sku":"%s","name":"Updated","description":"","category":"misc","priceMinor":%d,"currency":"USD","version":%d}"""
                .formatted(sku, price, version));
  }

  JsonNode getJson(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    return json.readTree(
        mvc.perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }
}
