package dev.commerceflow.payment.payment;

import dev.commerceflow.payment.payment.PaymentLedger.CancelStep;
import dev.commerceflow.payment.payment.PaymentLedger.Settlement;
import dev.commerceflow.payment.provider.ProviderClient;
import dev.commerceflow.payment.provider.ProviderClient.ChargeOutcome;
import dev.commerceflow.payment.provider.ProviderClient.ProviderCharge;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Charges and cancellations. Deliberately not transactional: each step that touches the database is
 * its own short transaction in {@link PaymentLedger}, and the provider is called between them,
 * never inside one.
 *
 * <p>Charge: TX1 records the attempt, then the provider is called with the attempt id as its
 * idempotency key, then TX2 records the outcome. A crash anywhere leaves either no attempt (the
 * command is redelivered and starts over), or an INITIATED one (the redelivery re-sends the charge
 * under the same key, which the provider answers without charging again).
 */
@Service
public class PaymentService {

  /** Rounds of "look up, then settle" a cancel tries when a concurrent settle keeps winning. */
  private static final int CANCEL_ROUNDS = 3;

  private final PaymentLedger ledger;
  private final ProviderClient provider;

  PaymentService(PaymentLedger ledger, ProviderClient provider) {
    this.ledger = ledger;
    this.provider = provider;
  }

  public void authorize(
      UUID orderId, UUID customerId, long amountMinor, String currency, String paymentToken) {
    Attempt attempt = ledger.open(orderId, customerId, amountMinor, currency, paymentToken);
    if (!attempt.inFlight()) {
      // Already settled, or cancelled before it was requested: answer, never charge again.
      ledger.answer(attempt);
      return;
    }
    // The stored request, not the command's: the attempt is what was promised to the provider.
    ChargeOutcome outcome =
        provider.charge(
            attempt.id(), attempt.amountMinor(), attempt.currency(), attempt.paymentTokenRef());
    record(attempt.id(), outcome);
  }

  /** TX2, and the refund if the attempt was cancelled while its charge was in flight. */
  void record(UUID attemptId, ChargeOutcome outcome) {
    if (ledger.settle(attemptId, outcome) == Settlement.REFUND_NEEDED) {
      refund(ledger.find(attemptId));
    }
  }

  /**
   * Void-or-refund. No attempt: a tombstone, so a late AuthorizePayment charges nothing. Settled
   * with money taken: refund. Unsettled: ask the provider what happened, then refund or cancel.
   * Every path ends in PaymentFailed(CANCELLED) or PaymentRefunded (or the recorded decline).
   */
  public void cancel(UUID orderId) {
    for (int round = 0; round < CANCEL_ROUNDS; round++) {
      CancelStep step = ledger.beginCancel(orderId);
      Attempt attempt = step.attempt();
      switch (step.kind()) {
        case DONE -> {
          return;
        }
        case REFUND -> {
          refund(attempt);
          return;
        }
        case LOOKUP -> {
          Optional<ProviderCharge> charge = provider.findCharge(attempt.id());
          if (charge.isEmpty()) {
            if (ledger.cancelUnsettled(attempt.id())) {
              return;
            }
          } else if (charge.get().succeeded()) {
            if (ledger.markRefundPending(attempt.id(), charge.get().id())) {
              refund(ledger.find(attempt.id()));
              return;
            }
          } else {
            ledger.settle(attempt.id(), new ChargeOutcome.Declined(charge.get().declineCode()));
            return;
          }
          // A concurrent settle changed the attempt between the lookup and here: decide again.
        }
      }
    }
    throw new IllegalStateException("cancel of order " + orderId + " kept racing; will retry");
  }

  /** Same idempotency key on every retry, so a refund retried after a lost reply refunds once. */
  private void refund(Attempt attempt) {
    String refundId =
        provider.refund(attempt.id(), attempt.providerChargeId(), attempt.amountMinor());
    ledger.recordRefund(attempt.id(), refundId);
  }
}
