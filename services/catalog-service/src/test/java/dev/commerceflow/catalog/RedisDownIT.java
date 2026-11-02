package dev.commerceflow.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.catalog.config.CacheConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The cache is an optimisation, not a dependency: with Redis unreachable, every read and write must
 * still succeed (slower), never fail with a 500.
 */
@SpringBootTest(properties = {"spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1"})
@AutoConfigureMockMvc
@Import({Containers.Postgres.class, TestTokens.class})
class RedisDownIT {

  @Autowired MockMvc mvc;
  @Autowired CacheManager cacheManager;

  /**
   * Pins down observed library behaviour (Spring Data Redis 4.1): against an unreachable server the
   * cache writer's reads throw, but its writes return normally. Consequence, documented in
   * ADR-0009: an eviction lost during an outage can leave an entry stale for up to its TTL. If an
   * upgrade changes this, this test fails and ResilientCache becomes the line of defence.
   */
  @Test
  void deadRedisFailsReadsLoudlyButWritesSilently() {
    Cache cache = cacheManager.getCache(CacheConfig.PRODUCT);
    RedisCacheWriter writer = (RedisCacheWriter) cache.getNativeCache();

    assertThatThrownBy(() -> cache.get("probe"))
        .isInstanceOf(RedisConnectionFailureException.class);
    assertThatCode(() -> writer.evict("catalog:product::", "probe".getBytes()))
        .doesNotThrowAnyException();
    assertThatCode(() -> writer.put("catalog:product::", "probe".getBytes(), "v".getBytes(), null))
        .doesNotThrowAnyException();
  }

  /** The deferred, after-commit eviction path completes without reaching the caller. */
  @Test
  void deferredEvictionAgainstDeadRedisDoesNotThrow() {
    Cache cache = cacheManager.getCache(CacheConfig.PRODUCT);
    TransactionSynchronizationManager.initSynchronization();
    try {
      cache.evict("probe-key");
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
      for (TransactionSynchronization sync :
          TransactionSynchronizationManager.getSynchronizations()) {
        assertThatCode(sync::afterCommit).doesNotThrowAnyException();
      }
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void browsingAndAdminEditsWorkWithoutRedis() throws Exception {
    String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String created =
        mvc.perform(
                post("/api/v1/admin/products")
                    .header("Authorization", TestTokens.admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CatalogApiIT.productJson(sku, "No cache", "misc", 100)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonMapper.builder().build().readTree(created).get("id").asString());

    long start = System.nanoTime();
    mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/products").param("q", "cache")).andExpect(status().isOk());
    mvc.perform(
            put("/api/v1/admin/products/" + id)
                .header("Authorization", TestTokens.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"sku":"%s","name":"No cache","description":"","category":"misc","priceMinor":250,"currency":"USD","version":0}"""
                        .formatted(sku)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/products/" + id)).andExpect(jsonPath("$.priceMinor").value(250));
    long millis = (System.nanoTime() - start) / 1_000_000;

    // Four requests, each allowed one fast-failing Redis attempt; nowhere near Lettuce's 60 s.
    assertThat(millis).isLessThan(10_000);
  }
}
