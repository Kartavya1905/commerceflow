package dev.commerceflow.order.order;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** What the API returns for an order. Prices are the ones captured when it was placed. */
public record OrderView(
    UUID id,
    String status,
    String failureReason,
    List<Item> items,
    long totalMinor,
    String currency,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  public record Item(UUID productId, String name, int quantity, long unitPriceMinor) {}
}
