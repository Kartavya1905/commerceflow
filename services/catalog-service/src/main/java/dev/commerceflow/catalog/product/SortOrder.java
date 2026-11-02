package dev.commerceflow.catalog.product;

/**
 * Allowed sort orders. Each maps to a fixed SQL fragment, so user input never reaches the ORDER BY
 * clause. The id tie-breaker keeps paging stable when values are equal.
 */
public enum SortOrder {
  relevance(
      "ts_rank(search_vector, websearch_to_tsquery('english', :q)) desc, created_at desc, id"),
  priceAsc("price_minor asc, id"),
  priceDesc("price_minor desc, id"),
  newest("created_at desc, id");

  private final String orderBy;

  SortOrder(String orderBy) {
    this.orderBy = orderBy;
  }

  /** Relevance needs search terms; without them it falls back to newest. */
  String orderBy(boolean hasQuery) {
    return this == relevance && !hasQuery ? newest.orderBy : orderBy;
  }
}
