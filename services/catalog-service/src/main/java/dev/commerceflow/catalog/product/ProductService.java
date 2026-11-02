package dev.commerceflow.catalog.product;

import dev.commerceflow.catalog.config.CacheConfig;
import dev.commerceflow.web.ApiException;
import dev.commerceflow.web.audit.AuditWriter;
import dev.commerceflow.web.audit.AuditWriter.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin writes. Cache evictions are declared here but executed only after commit (the cache manager
 * is transaction-aware), so a failed write never clears the cache.
 */
@Service
public class ProductService {

  private static final String ADMIN = "ADMIN";

  private final ProductRepository products;
  private final AuditWriter audit;
  private final Clock clock;

  public ProductService(ProductRepository products, AuditWriter audit, Clock clock) {
    this.products = products;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  @CacheEvict(cacheNames = CacheConfig.PRODUCT_PAGES, allEntries = true)
  public ProductView create(ProductFields fields, UUID actor) {
    Product product = Product.create(fields, clock.instant());
    try {
      products.saveAndFlush(product);
    } catch (DataIntegrityViolationException e) {
      throw skuTaken(fields.sku());
    }
    audit.record(
        actor,
        ADMIN,
        "PRODUCT_CREATED",
        "product",
        product.id().toString(),
        Outcome.SUCCESS,
        Map.of("sku", fields.sku(), "priceMinor", fields.priceMinor()));
    return product.toView();
  }

  /**
   * Optimistic locking: the admin sends the version they last read. A mismatch means someone else
   * changed the product in between, and silently overwriting their edit would lose it.
   */
  @Transactional
  @Caching(
      evict = {
        @CacheEvict(cacheNames = CacheConfig.PRODUCT, key = "#id"),
        @CacheEvict(cacheNames = CacheConfig.PRODUCT_PAGES, allEntries = true)
      })
  public ProductView update(UUID id, ProductFields fields, long expectedVersion, UUID actor) {
    Product product = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
    if (product.version() != expectedVersion) {
      throw ApiException.conflict(
          "stale-version", "The product was changed by someone else. Reload it and try again.");
    }
    long oldPrice = product.priceMinor();
    product.apply(fields, clock.instant());
    try {
      products.saveAndFlush(product);
    } catch (DataIntegrityViolationException e) {
      throw skuTaken(fields.sku());
    }
    audit.record(
        actor,
        ADMIN,
        "PRODUCT_UPDATED",
        "product",
        id.toString(),
        Outcome.SUCCESS,
        Map.of("oldPriceMinor", oldPrice, "newPriceMinor", fields.priceMinor()));
    return product.toView();
  }

  @Transactional
  @Caching(
      evict = {
        @CacheEvict(cacheNames = CacheConfig.PRODUCT, key = "#id"),
        @CacheEvict(cacheNames = CacheConfig.PRODUCT_PAGES, allEntries = true)
      })
  public void archive(UUID id, UUID actor) {
    Product product = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
    Instant now = clock.instant();
    if (product.archive(now)) {
      audit.record(
          actor, ADMIN, "PRODUCT_ARCHIVED", "product", id.toString(), Outcome.SUCCESS, Map.of());
    }
  }

  private static ApiException skuTaken(String sku) {
    return ApiException.conflict("sku-taken", "A product with SKU " + sku + " already exists.");
  }
}
