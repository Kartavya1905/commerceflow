package dev.commerceflow.payment.payment;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * "Reconcile now", for admins: brings an attempt's next look forward to the reconciler's next round
 * (within {@code reconcile-interval}, 10 s). It does not call the provider itself: the look goes
 * through the reconciler's usual claim and guarded updates, so asking twice, or asking while a
 * round is already looking, still resolves the attempt once.
 */
@Component
public class ManualReconciliation {

  /** What asking for a look now led to. */
  public enum Result {
    SCHEDULED,
    /** Settled: there is nothing left to look for. */
    FINAL,
    NOT_FOUND
  }

  /**
   * @param status the attempt's status when asked, for the audit record; null if not found
   */
  public record Outcome(Result result, String status) {}

  private final PaymentLedger ledger;

  ManualReconciliation(PaymentLedger ledger) {
    this.ledger = ledger;
  }

  public Outcome requestLook(UUID attemptId) {
    return ledger
        .lookNow(attemptId)
        .map(a -> new Outcome(Result.SCHEDULED, a.status()))
        .orElseGet(
            () ->
                ledger
                    .findById(attemptId)
                    .map(a -> new Outcome(Result.FINAL, a.status()))
                    .orElse(new Outcome(Result.NOT_FOUND, null)));
  }
}
