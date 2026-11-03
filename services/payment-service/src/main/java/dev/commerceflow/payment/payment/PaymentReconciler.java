package dev.commerceflow.payment.payment;

import dev.commerceflow.payment.config.PaymentConfig.PaymentProperties;
import dev.commerceflow.payment.payment.PaymentLedger.Backlog;
import dev.commerceflow.payment.provider.ProviderClient;
import dev.commerceflow.payment.provider.ProviderClient.ChargeOutcome;
import dev.commerceflow.payment.provider.ProviderClient.ProviderCharge;
import dev.commerceflow.payment.provider.ProviderClient.ProviderUnavailableException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * The payment reconciler (ARCHITECTURE.md E.4, G.4). Asks the provider, by idempotency key, what
 * happened to attempts whose outcome nobody recorded:
 *
 * <ul>
 *   <li>UNKNOWN (the charge call timed out or errored) and INITIATED left behind by a crash: a
 *       charge found is recorded as the answer, exactly as if the call had returned; a decline
 *       likewise. With no charge for the whole reconciliation window, the attempt fails.
 *   <li>Attempts closed without a charge (cancelled, or failed by this reconciler) get one later
 *       look, for a charge that was still on its way to the provider: if it landed, it is refunded.
 * </ul>
 *
 * <p>The provider is called with no transaction open. The result is written with the same guarded
 * updates the charge path uses, so the reconciler and a late provider answer (or a redelivered
 * command) can both try to resolve one attempt and only the first changes anything.
 */
@Component
class PaymentReconciler {

  private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

  /** What one look at one attempt led to. */
  enum Look {
    /** Someone else took it, or it is no longer due. */
    SKIPPED,
    /** The provider had the charge (or decline); recorded as the attempt's outcome. */
    RESOLVED,
    /** Still no charge, still inside the window: looked at again later. */
    STILL_UNKNOWN,
    /** No charge for the whole window: the attempt failed. */
    UNRESOLVED,
    /** A charge landed on a closed attempt and was refunded. */
    LATE_CHARGE_REFUNDED,
    /** A closed attempt's last look found no charge. */
    CLOSED,
    /** A refund left pending by a failed refund call went through this time. */
    REFUNDED,
    /** The provider could not be asked; looked at again later. */
    PROVIDER_UNAVAILABLE
  }

  private final PaymentLedger ledger;
  private final PaymentService payments;
  private final ProviderClient provider;
  private final PaymentProperties properties;
  private final MeterRegistry meters;
  private final Clock clock;
  private final AtomicReference<Backlog> backlog = new AtomicReference<>(new Backlog(0, 0));

  PaymentReconciler(
      PaymentLedger ledger,
      PaymentService payments,
      ProviderClient provider,
      PaymentProperties properties,
      MeterRegistry meters,
      Clock clock) {
    this.ledger = ledger;
    this.payments = payments;
    this.provider = provider;
    this.properties = properties;
    this.meters = meters;
    this.clock = clock;
    Gauge.builder("payment.reconciliation.open", backlog, b -> b.get().open())
        .description("Attempts whose outcome is not yet known")
        .register(meters);
    Gauge.builder("payment.reconciliation.oldest.age", backlog, b -> b.get().oldestSeconds())
        .description("Seconds since the oldest attempt with an unknown outcome was opened")
        .baseUnit("seconds")
        .register(meters);
  }

  @Scheduled(
      fixedDelayString = "${commerceflow.payment.reconcile-interval}",
      initialDelayString = "${commerceflow.payment.reconcile-interval}")
  void scheduled() {
    try {
      reconcile();
    } catch (DataAccessException | TransactionException e) {
      log.warn("Reconciliation round skipped: {}", e.getMessage());
    }
  }

  /**
   * @return how many attempts each outcome had in this round
   */
  Map<Look, Integer> reconcile() {
    Map<Look, Integer> outcomes = new EnumMap<>(Look.class);
    for (UUID attemptId : ledger.dueForReconciliation(properties.reconcileBatch())) {
      Look look;
      try {
        look = reconcile(attemptId);
      } catch (DataAccessException | TransactionException e) {
        throw e;
      } catch (RuntimeException e) {
        log.error("Reconciling attempt {} failed; it comes due again", attemptId, e);
        meters.counter("payment.reconcile.errors").increment();
        continue;
      }
      outcomes.merge(look, 1, Integer::sum);
    }
    backlog.set(ledger.backlog());
    return outcomes;
  }

  Look reconcile(UUID attemptId) {
    Optional<Attempt> claimed = ledger.claimForReconciliation(attemptId);
    if (claimed.isEmpty()) {
      return Look.SKIPPED;
    }
    Attempt attempt = claimed.get();
    if ("REFUND_PENDING".equals(attempt.status())) {
      try {
        payments.retryRefund(attempt);
      } catch (ProviderUnavailableException e) {
        log.warn("Refund of attempt {} failed again: {}", attempt.id(), e.getMessage());
        meters.counter("payment.reconcile.provider_unavailable").increment();
        return Look.PROVIDER_UNAVAILABLE;
      }
      meters.counter("payment.reconcile.looks", "result", Look.REFUNDED.name()).increment();
      return Look.REFUNDED;
    }
    Optional<ProviderCharge> charge;
    try {
      charge = provider.findCharge(attempt.id());
    } catch (ProviderUnavailableException e) {
      log.warn("Could not reconcile attempt {}: {}", attempt.id(), e.getMessage());
      meters.counter("payment.reconcile.provider_unavailable").increment();
      return Look.PROVIDER_UNAVAILABLE;
    }
    Look look = attempt.inFlight() ? settle(attempt, charge) : lookAgain(attempt, charge);
    meters.counter("payment.reconcile.looks", "result", look.name()).increment();
    return look;
  }

  private Look settle(Attempt attempt, Optional<ProviderCharge> charge) {
    if (charge.isPresent()) {
      ChargeOutcome outcome =
          charge.get().succeeded()
              ? new ChargeOutcome.Succeeded(charge.get().id())
              : new ChargeOutcome.Declined(charge.get().declineCode());
      log.info("Reconciled attempt {}: provider says {}", attempt.id(), charge.get().status());
      meters
          .counter(
              "payment.reconciled", "outcome", charge.get().succeeded() ? "SUCCEEDED" : "FAILED")
          .increment();
      payments.record(attempt.id(), outcome);
      return Look.RESOLVED;
    }
    Duration open = Duration.between(attempt.createdAt(), OffsetDateTime.now(clock));
    if (open.compareTo(properties.reconcileWindow()) < 0) {
      return Look.STILL_UNKNOWN;
    }
    if (ledger.resolveUnresolved(attempt.id())) {
      log.warn("Attempt {} unresolved after {}: no charge at the provider", attempt.id(), open);
      return Look.UNRESOLVED;
    }
    return Look.SKIPPED;
  }

  private Look lookAgain(Attempt attempt, Optional<ProviderCharge> charge) {
    if (charge.isPresent() && charge.get().succeeded()) {
      log.warn("A charge landed on closed attempt {}; refunding it", attempt.id());
      payments.record(attempt.id(), new ChargeOutcome.Succeeded(charge.get().id()));
      return Look.LATE_CHARGE_REFUNDED;
    }
    ledger.finishLook(attempt.id());
    return Look.CLOSED;
  }
}
