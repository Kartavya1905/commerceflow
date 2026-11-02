package dev.commerceflow.order.saga;

import java.util.Optional;

/**
 * What the saga reacts to. Finer than the wire event types where the saga's answer depends on a
 * reason: a PaymentFailed that was a decline, a timeout, or a cancellation leads to different
 * places, and the transition table should say so itself rather than hide it in code.
 */
public enum SagaEvent {
  INVENTORY_RESERVED,
  INVENTORY_REJECTED,
  INVENTORY_COMMITTED,
  INVENTORY_COMMIT_FAILED,
  INVENTORY_RELEASED,
  PAYMENT_SUCCEEDED,
  /** PaymentFailed(DECLINED or INSUFFICIENT_FUNDS). */
  PAYMENT_DECLINED,
  /** PaymentFailed(TIMEOUT_UNRESOLVED): reconciliation found no charge. */
  PAYMENT_TIMED_OUT,
  /** PaymentFailed(CANCELLED): the answer to CancelPayment when nothing was charged. */
  PAYMENT_CANCELLED,
  PAYMENT_PENDING_RECONCILIATION,
  PAYMENT_REFUNDED,
  /** The current step's deadline passed after its last re-send (the sweeper, Milestone 7). */
  DEADLINE_EXCEEDED;

  /**
   * @param reason the payload's reason field, for the event types that have one
   * @return empty for an event the saga has no use for
   */
  public static Optional<SagaEvent> of(String eventType, String reason) {
    SagaEvent event =
        switch (eventType) {
          case "InventoryReserved" -> INVENTORY_RESERVED;
          case "InventoryRejected" -> INVENTORY_REJECTED;
          case "InventoryCommitted" -> INVENTORY_COMMITTED;
          case "InventoryCommitFailed" -> INVENTORY_COMMIT_FAILED;
          case "InventoryReleased" -> INVENTORY_RELEASED;
          case "PaymentSucceeded" -> PAYMENT_SUCCEEDED;
          case "PaymentPendingReconciliation" -> PAYMENT_PENDING_RECONCILIATION;
          case "PaymentRefunded" -> PAYMENT_REFUNDED;
          case "PaymentFailed" ->
              switch (reason == null ? "" : reason) {
                case "DECLINED", "INSUFFICIENT_FUNDS" -> PAYMENT_DECLINED;
                case "TIMEOUT_UNRESOLVED" -> PAYMENT_TIMED_OUT;
                case "CANCELLED" -> PAYMENT_CANCELLED;
                default -> null;
              };
          default -> null;
        };
    return Optional.ofNullable(event);
  }
}
