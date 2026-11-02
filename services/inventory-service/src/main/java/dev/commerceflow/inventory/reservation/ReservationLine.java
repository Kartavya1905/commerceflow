package dev.commerceflow.inventory.reservation;

import java.util.UUID;

/** One product and quantity in an order's reservation. */
public record ReservationLine(UUID productId, int quantity) {

  public ReservationLine {
    if (productId == null) {
      throw new IllegalArgumentException("productId is required");
    }
    if (quantity < 1 || quantity > 1000) {
      throw new IllegalArgumentException("quantity must be between 1 and 1000");
    }
  }
}
