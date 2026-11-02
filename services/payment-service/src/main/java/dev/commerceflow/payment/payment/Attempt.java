package dev.commerceflow.payment.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One payment attempt: the only one an order will ever have. A tombstone (cancelled before it was
 * requested) has no customer, amount or token.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Attempt(
    UUID id,
    UUID orderId,
    UUID customerId,
    Long amountMinor,
    String currency,
    String paymentTokenRef,
    String status,
    String providerChargeId,
    String providerRefundId,
    String failureCode,
    int reconcileAttempts,
    OffsetDateTime nextReconcileAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  /** The charge has not been settled yet: it may still be (re)sent to the provider. */
  public boolean inFlight() {
    return "INITIATED".equals(status) || "UNKNOWN".equals(status);
  }

  public static Attempt map(ResultSet rs, int row) throws SQLException {
    return new Attempt(
        rs.getObject("id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getObject("amount_minor", Long.class),
        rs.getString("currency"),
        rs.getString("payment_token_ref"),
        rs.getString("status"),
        rs.getString("provider_charge_id"),
        rs.getString("provider_refund_id"),
        rs.getString("failure_code"),
        rs.getInt("reconcile_attempts"),
        rs.getObject("next_reconcile_at", OffsetDateTime.class),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }
}
