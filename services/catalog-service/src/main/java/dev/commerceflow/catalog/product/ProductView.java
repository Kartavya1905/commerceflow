package dev.commerceflow.catalog.product;

import java.util.UUID;

/** Read model returned by the API and stored in the cache. Money is in minor units. */
public record ProductView(
    UUID id,
    String sku,
    String name,
    String description,
    String category,
    long priceMinor,
    String currency,
    String status,
    long version) {}
