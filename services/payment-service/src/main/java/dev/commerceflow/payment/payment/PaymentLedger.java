package dev.commerceflow.payment.payment;

import dev.commerceflow.messaging.Backoff;
import dev.commerceflow.messaging.OutboxWriter;
import dev.commerceflow.messaging.Uuids;
import dev.commerceflow.payment.config.PaymentConfig.PaymentProperties;
import dev.commerceflow.payment.provider.ProviderClient.ChargeOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions around a provider call. Each status change is a guarded UPDATE (only from
 * the statuses it may come from), and each one writes its event to the outbox in the same
 * transaction. Nothing here talks to the provider.
 */
@Component
class PaymentLedger {

  static final String AGGREGATE = "OrderPayment";

  /** What settling a charge outcome led to. */
  enum Settlement {
    RECORDED,
    /** The attempt was cancelled while the charge was in flight, and the charge went through. */
    REFUND_NEEDED,
    /** Someone else settled it first; nothing changed. */
    SUPERSEDED
  }

  /** What a CancelPayment has to do next, outside the transaction. */
  record CancelStep(Kind kind, Attempt attempt) {
    enum Kind {
      /** Answered from what is recorded; nothing more to do. */
      DONE,
      /** Money was taken: refund it. */
      REFUND,
      /** The charge's fate is unknown: ask the provider before deciding. */
      LOOKUP
    }
  }

  private final JdbcClient jdbc;
  private final OutboxWriter outbox;
  private final MeterRegistry meters;
  private final Clock clock;
  private final PaymentProperties properties;

  PaymentLedger(
      JdbcClient jdbc,
      OutboxWriter outbox,
      MeterRegistry meters,
      Clock clock,
      PaymentProperties properties) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.meters = meters;
    this.clock = clock;
    this.properties = properties;
  }

  /**
   * TX1, the write-ahead record: the attempt exists, and is committed, before the provider is asked
   * to charge. Returns the order's existing attempt if it already has one (a duplicate command, a
   * redelivery, or a cancellation tombstone).
   */
  @Transactional
  Attempt open(UUID orderId, UUID customerId, long amountMinor, String currency, String token) {
    OffsetDateTime now = now();
    jdbc.sql(
            """
            insert into payment_attempts (id, order_id, customer_id, amount_minor, currency,
                                          payment_token_ref, status, created_at, updated_at)
            values (:id, :order, :customer, :amount, :currency, :token, 'INITIATED', :now, :now)
            on conflict (order_id) do nothing
            """)
        .param("id", Uuids.v7())
        .param("order", orderId)
        .param("customer", customerId)
        .param("amount", amountMinor)
        .param("currency", currency)
        .param("token", token)
        .param("now", now)
        .update();
    return findByOrder(orderId).orElseThrow();
  }

  /**
   * Answers a repeated command for a settled attempt with its recorded outcome, so a saga that
   * re-sends a command because it missed the answer hears it again.
   */
  @Transactional
  void answer(Attempt attempt) {
    switch (attempt.status()) {
      case "SUCCEEDED" -> succeeded(attempt, attempt.providerChargeId());
      case "FAILED" -> failed(attempt, failureReason(attempt.failureCode()));
      case "CANCELLED" -> failed(attempt, "CANCELLED");
      case "REFUNDED" -> refunded(attempt, attempt.providerRefundId());
      default -> {
        // REFUND_PENDING: the refund is under way and will publish when it completes.
      }
    }
  }

  /** TX2: records what the provider said, if the attempt is still waiting for it. */
  @Transactional
  Settlement settle(UUID attemptId, ChargeOutcome outcome) {
    String status;
    String chargeId = null;
    String code;
    switch (outcome) {
      case ChargeOutcome.Succeeded s -> {
        status = "SUCCEEDED";
        chargeId = s.chargeId();
        code = null;
      }
      case ChargeOutcome.Declined d -> {
        status = "FAILED";
        code = d.code();
      }
      case ChargeOutcome.Unknown u -> {
        // Never FAILED: the charge may have gone through, and failing would release the stock of
        // an order that was paid for. The reconciler resolves it from the provider's records.
        status = "UNKNOWN";
        code = u.reason();
      }
    }
    OffsetDateTime now = now();
    int moved =
        jdbc.sql(
                """
                update payment_attempts
                   set status = :status, provider_charge_id = :charge, failure_code = :code,
                       next_reconcile_at = :reconcileAt, updated_at = :now
                 where id = :id and status in ('INITIATED', 'UNKNOWN')
                """)
            .param("status", status)
            .param("charge", chargeId)
            .param("code", code)
            .param(
                "reconcileAt",
                "UNKNOWN".equals(status) ? now.plus(properties.reconcileAfter()) : null)
            .param("now", now)
            .param("id", attemptId)
            .update();
    Attempt attempt = find(attemptId);
    if (moved == 0) {
      if (chargeId != null
          && ("CANCELLED".equals(attempt.status()) || "FAILED".equals(attempt.status()))) {
        // The attempt was closed without a charge (the cancel, or the reconciler giving up, found
        // none), but the charge was still in flight and went through. A decline cannot turn into
        // a success under the same key, so this money belongs to an order that failed (INV-5).
        markRefundPending(attemptId, chargeId);
        meters.counter("payment.late_charge_refunded").increment();
        return Settlement.REFUND_NEEDED;
      }
      meters.counter("payment.settle.superseded").increment();
      return Settlement.SUPERSEDED;
    }
    meters.counter("payment.attempts", "outcome", status).increment();
    switch (status) {
      case "SUCCEEDED" -> succeeded(attempt, chargeId);
      case "FAILED" -> failed(attempt, failureReason(code));
      default -> pendingReconciliation(attempt, code);
    }
    return Settlement.RECORDED;
  }

  /**
   * Locks the order's attempt and decides what cancelling it takes. With no attempt yet, leaves a
   * CANCELLED tombstone: an AuthorizePayment that arrives later meets it and charges nothing.
   */
  @Transactional
  CancelStep beginCancel(UUID orderId) {
    Optional<Attempt> locked =
        jdbc.sql("select * from payment_attempts where order_id = :order for update")
            .param("order", orderId)
            .query(Attempt::map)
            .optional();
    if (locked.isEmpty()) {
      OffsetDateTime now = now();
      jdbc.sql(
              """
              insert into payment_attempts (id, order_id, status, failure_code, created_at,
                                            updated_at)
              values (:id, :order, 'CANCELLED', 'cancelled_before_request', :now, :now)
              on conflict (order_id) do nothing
              """)
          .param("id", Uuids.v7())
          .param("order", orderId)
          .param("now", now)
          .update();
      Attempt tombstone = findByOrder(orderId).orElseThrow();
      meters.counter("payment.tombstones").increment();
      failed(tombstone, "CANCELLED");
      return new CancelStep(CancelStep.Kind.DONE, tombstone);
    }
    Attempt attempt = locked.get();
    return switch (attempt.status()) {
      case "SUCCEEDED" -> {
        markRefundPending(attempt.id(), attempt.providerChargeId());
        yield new CancelStep(CancelStep.Kind.REFUND, find(attempt.id()));
      }
      case "REFUND_PENDING" -> new CancelStep(CancelStep.Kind.REFUND, attempt);
      case "INITIATED", "UNKNOWN" -> new CancelStep(CancelStep.Kind.LOOKUP, attempt);
      default -> {
        answer(attempt);
        yield new CancelStep(CancelStep.Kind.DONE, attempt);
      }
    };
  }

  /**
   * The provider has no charge for an unsettled attempt: cancel it so it can never charge. The
   * reconciler looks once more later, in case a charge request was still on its way to the provider
   * and this process dies before its answer is recorded (and refunded).
   */
  @Transactional
  boolean cancelUnsettled(UUID attemptId) {
    OffsetDateTime now = now();
    int moved =
        jdbc.sql(
                """
                update payment_attempts
                   set status = 'CANCELLED', failure_code = 'cancelled_before_charge',
                       next_reconcile_at = :check, updated_at = :now
                 where id = :id and status in ('INITIATED', 'UNKNOWN')
                """)
            .param("check", now.plus(properties.reconcileAfter()))
            .param("now", now)
            .param("id", attemptId)
            .update();
    if (moved == 1) {
      failed(find(attemptId), "CANCELLED");
    }
    return moved == 1;
  }

  /** Money was taken for an attempt that is being cancelled: it must be given back. */
  @Transactional
  boolean markRefundPending(UUID attemptId, String chargeId) {
    return jdbc.sql(
                """
                update payment_attempts
                   set status = 'REFUND_PENDING', provider_charge_id = :charge,
                       next_reconcile_at = null, updated_at = :now
                 where id = :id
                   and status in ('SUCCEEDED', 'INITIATED', 'UNKNOWN', 'CANCELLED', 'FAILED')
                """)
            .param("charge", chargeId)
            .param("now", now())
            .param("id", attemptId)
            .update()
        == 1;
  }

  @Transactional
  void recordRefund(UUID attemptId, String refundId) {
    int moved =
        jdbc.sql(
                """
                update payment_attempts
                   set status = 'REFUNDED', provider_refund_id = :refund, next_reconcile_at = null,
                       updated_at = :now
                 where id = :id and status = 'REFUND_PENDING'
                """)
            .param("refund", refundId)
            .param("now", now())
            .param("id", attemptId)
            .update();
    if (moved == 1) {
      meters.counter("payment.refunds").increment();
      refunded(find(attemptId), refundId);
    }
  }

  // --- Reconciliation ------------------------------------------------------------------------

  /**
   * Attempts the reconciler should look at: UNKNOWN ones when their next look is due, INITIATED
   * ones left behind (by a crash between recording the attempt and recording the provider's answer)
   * once they are older than any live charge call can be, and closed attempts that get one more
   * look for a charge that may have landed after they were closed. Also refunds left pending
   * because the refund call failed: nothing else would retry one that started from a late charge.
   */
  private static final String DUE =
      """
      (status = 'UNKNOWN' and next_reconcile_at <= :now)
      or (status in ('INITIATED', 'REFUND_PENDING')
          and coalesce(next_reconcile_at, updated_at + make_interval(secs => :stale)) <= :now)
      or (status in ('CANCELLED', 'FAILED') and next_reconcile_at <= :now)
      """;

  @Transactional(readOnly = true)
  List<UUID> dueForReconciliation(int limit) {
    return jdbc.sql(
            "select id from payment_attempts where "
                + DUE
                + " order by coalesce(next_reconcile_at, updated_at) limit :limit")
        .param("now", now())
        .param("stale", properties.reconcileStaleInitiated().toSeconds())
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  /**
   * Takes one due attempt by moving its next look into the future, with backoff. Whoever moves it
   * owns this look; another reconciler instance (or a second round) finds it no longer due. If the
   * look then fails or this process dies, the attempt simply comes due again.
   */
  @Transactional
  Optional<Attempt> claimForReconciliation(UUID attemptId) {
    OffsetDateTime now = now();
    Optional<Attempt> attempt =
        jdbc.sql("select * from payment_attempts where id = :id and (" + DUE + ") for update")
            .param("id", attemptId)
            .param("now", now)
            .param("stale", properties.reconcileStaleInitiated().toSeconds())
            .query(Attempt::map)
            .optional();
    attempt.ifPresent(
        a ->
            jdbc.sql(
                    """
                    update payment_attempts
                       set reconcile_attempts = reconcile_attempts + 1, next_reconcile_at = :next
                     where id = :id
                    """)
                .param(
                    "next",
                    now.plus(
                        Backoff.fullJitter(
                            a.reconcileAttempts(),
                            properties.reconcileAfter(),
                            properties.reconcileBackoffMax())))
                .param("id", a.id())
                .update());
    return attempt;
  }

  /**
   * The provider still has no charge for an attempt whose outcome has been unknown for the whole
   * reconciliation window: it fails, and the saga releases the stock. One more look is scheduled,
   * like a cancel's, for a charge that lands after all; it would be refunded.
   */
  @Transactional
  boolean resolveUnresolved(UUID attemptId) {
    OffsetDateTime now = now();
    int moved =
        jdbc.sql(
                """
                update payment_attempts
                   set status = 'FAILED', failure_code = 'timeout_unresolved',
                       next_reconcile_at = :check, updated_at = :now
                 where id = :id and status in ('INITIATED', 'UNKNOWN')
                """)
            .param("check", now.plus(properties.reconcileAfter()))
            .param("now", now)
            .param("id", attemptId)
            .update();
    if (moved == 1) {
      meters.counter("payment.reconciled", "outcome", "UNRESOLVED").increment();
      failed(find(attemptId), "TIMEOUT_UNRESOLVED");
    }
    return moved == 1;
  }

  /** The last look at a closed attempt found no charge: nothing more to check. */
  @Transactional
  void finishLook(UUID attemptId) {
    jdbc.sql(
            """
            update payment_attempts set next_reconcile_at = null
             where id = :id and status in ('CANCELLED', 'FAILED')
            """)
        .param("id", attemptId)
        .update();
  }

  /** Attempts whose outcome is still open, and how long the oldest has been open. */
  @Transactional(readOnly = true)
  Backlog backlog() {
    return jdbc.sql(
            """
            select count(*) as open,
                   coalesce(extract(epoch from (:now - min(created_at))), 0) as oldest
              from payment_attempts
             where status in ('INITIATED', 'UNKNOWN')
            """)
        .param("now", now())
        .query((rs, n) -> new Backlog(rs.getLong("open"), rs.getDouble("oldest")))
        .single();
  }

  record Backlog(long open, double oldestSeconds) {}

  @Transactional(readOnly = true)
  Attempt find(UUID attemptId) {
    return jdbc.sql("select * from payment_attempts where id = :id")
        .param("id", attemptId)
        .query(Attempt::map)
        .single();
  }

  @Transactional(readOnly = true)
  Optional<Attempt> findByOrder(UUID orderId) {
    return jdbc.sql("select * from payment_attempts where order_id = :order")
        .param("order", orderId)
        .query(Attempt::map)
        .optional();
  }

  // --- Events, written in the caller's transaction ------------------------------------------

  private void succeeded(Attempt attempt, String chargeId) {
    outbox.append(
        "PaymentSucceeded",
        AGGREGATE,
        attempt.orderId(),
        Map.of(
            "orderId", attempt.orderId(),
            "paymentAttemptId", attempt.id(),
            "providerChargeId", chargeId,
            "amountMinor", attempt.amountMinor(),
            "currency", attempt.currency()));
  }

  private void failed(Attempt attempt, String reason) {
    outbox.append(
        "PaymentFailed",
        AGGREGATE,
        attempt.orderId(),
        Map.of("orderId", attempt.orderId(), "paymentAttemptId", attempt.id(), "reason", reason));
  }

  private void pendingReconciliation(Attempt attempt, String reason) {
    outbox.append(
        "PaymentPendingReconciliation",
        AGGREGATE,
        attempt.orderId(),
        Map.of("orderId", attempt.orderId(), "paymentAttemptId", attempt.id(), "reason", reason));
  }

  private void refunded(Attempt attempt, String refundId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderId", attempt.orderId());
    payload.put("paymentAttemptId", attempt.id());
    payload.put("providerRefundId", refundId);
    payload.put("amountMinor", attempt.amountMinor());
    payload.put("currency", attempt.currency());
    outbox.append("PaymentRefunded", AGGREGATE, attempt.orderId(), payload);
  }

  static String failureReason(String declineCode) {
    return "insufficient_funds".equals(declineCode) ? "INSUFFICIENT_FUNDS" : "DECLINED";
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
  }
}
