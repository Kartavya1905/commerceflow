package dev.commerceflow.catalog.product;

import dev.commerceflow.catalog.config.CacheConfig;
import dev.commerceflow.web.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side: public browsing (cached) and authoritative prices for checkout (never cached). */
@Service
@Transactional(readOnly = true)
public class ProductQueries {

  private static final String COLUMNS =
      "id, sku, name, description, category, price_minor, currency, status, version";

  private final JdbcClient jdbc;

  public ProductQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Cacheable(cacheNames = CacheConfig.PRODUCT, key = "#id")
  public ProductView getActive(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from products where id = :id and status = 'ACTIVE'")
        .param("id", id)
        .query(ProductQueries::map)
        .optional()
        .orElseThrow(() -> ApiException.notFound("Product"));
  }

  @Cacheable(
      cacheNames = CacheConfig.PRODUCT_PAGES,
      key =
          "T(dev.commerceflow.catalog.product.ProductQueries).pageKey(#q, #category, #sort, #page, #size)")
  public ProductPage search(String q, String category, SortOrder sort, int page, int size) {
    String query = blankToNull(q);
    String cat = blankToNull(category);
    String where =
        " from products where status = 'ACTIVE'"
            + " and (cast(:category as text) is null or category = :category)"
            + " and (cast(:q as text) is null or search_vector @@ websearch_to_tsquery('english', :q))";

    List<ProductView> items =
        jdbc.sql(
                "select "
                    + COLUMNS
                    + where
                    + " order by "
                    + sort.orderBy(query != null)
                    + " limit :limit offset :offset")
            .param("q", query)
            .param("category", cat)
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(ProductQueries::map)
            .list();
    long total =
        jdbc.sql("select count(*)" + where)
            .param("q", query)
            .param("category", cat)
            .query(Long.class)
            .single();
    return new ProductPage(items, page, size, total);
  }

  /**
   * Current prices of active products, read straight from the database. Checkout must never price
   * an order from a cache that could be up to a minute stale.
   */
  public List<ProductView> activePrices(Collection<UUID> ids) {
    return jdbc.sql("select " + COLUMNS + " from products where id in (:ids) and status = 'ACTIVE'")
        .param("ids", ids)
        .query(ProductQueries::map)
        .list();
  }

  /**
   * Cache key for a search. Values are URL-encoded and absent ones marked with "~", so different
   * inputs can never produce the same key (a literal search for "null" is not "no search").
   */
  public static String pageKey(String q, String category, SortOrder sort, int page, int size) {
    return String.join("|", encode(q), encode(category), sort.name(), "" + page, "" + size);
  }

  private static String encode(String value) {
    String v = blankToNull(value);
    return v == null ? "~" : "=" + URLEncoder.encode(v, StandardCharsets.UTF_8);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static ProductView map(ResultSet rs, int row) throws SQLException {
    return new ProductView(
        rs.getObject("id", UUID.class),
        rs.getString("sku"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("category"),
        rs.getLong("price_minor"),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getLong("version"));
  }
}
