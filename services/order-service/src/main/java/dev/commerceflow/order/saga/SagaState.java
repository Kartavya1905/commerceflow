package dev.commerceflow.order.saga;

/** The checkout saga's internal states (ARCHITECTURE.md section E.2). */
public enum SagaState {
  AWAITING_RESERVATION,
  AWAITING_PAYMENT,
  /** The payment's outcome is unknown; payment-service is finding out. */
  AWAITING_RECONCILIATION,
  /** Gave up waiting for payment: cancel it (void, or refund if it went through). */
  CANCELLING_PAYMENT,
  AWAITING_COMMIT,
  /** Paid, but the stock could not be committed: give the money back. */
  REFUNDING_PAYMENT,
  RELEASING_INVENTORY,
  COMPLETED,
  FAILED;

  public boolean terminal() {
    return this == COMPLETED || this == FAILED;
  }
}
