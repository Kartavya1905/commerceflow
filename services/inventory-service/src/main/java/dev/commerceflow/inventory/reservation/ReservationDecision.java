package dev.commerceflow.inventory.reservation;

import java.util.List;
import java.util.UUID;

/**
 * The stored outcome for one order. {@code replay} is true when the call found an existing decision
 * instead of making one: the caller must answer the same way it did the first time.
 */
public record ReservationDecision(
    UUID orderId,
    Status status,
    RejectReason rejectReason,
    List<UUID> rejectedProductIds,
    List<ReservationLine> lines,
    boolean replay) {

  public enum Status {
    RESERVED,
    REJECTED,
    COMMITTED,
    RELEASED,
    EXPIRED
  }

  /** Same values as InventoryRejected.reason in the event contract. */
  public enum RejectReason {
    OUT_OF_STOCK,
    UNKNOWN_PRODUCT
  }

  ReservationDecision asReplay() {
    return new ReservationDecision(orderId, status, rejectReason, rejectedProductIds, lines, true);
  }
}
