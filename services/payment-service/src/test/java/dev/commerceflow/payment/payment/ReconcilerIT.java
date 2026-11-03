package dev.commerceflow.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.commerceflow.payment.PaymentTestBase;
import dev.commerceflow.payment.payment.PaymentReconciler.Look;
import dev.commerceflow.payment.provider.ProviderClient;
import dev.commerceflow.payment.provider.ProviderClient.ChargeOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reconciler against the real sandbox. Each test sets up what a lost answer or a crash leaves
 * behind, makes the attempt due, and runs a look itself (the scheduled round is slowed to hourly).
 */
class ReconcilerIT extends PaymentTestBase {

  @Autowired PaymentReconciler reconciler;
  @Autowired PaymentLedger ledger;
  @Autowired PaymentService payments;
  @Autowired ProviderClient provider;
  @Autowired JdbcClient jdbc;
  @Autowired MeterRegistry meters;
  final JsonMapper json = JsonMapper.builder().build();

  /** The roadmap's lost-ack case: charged, but the answer never came back. */
  @Test
  void aTimedOutChargeThatWentThroughIsFoundAndRecordedOnce() throws Exception {
    UUID order = UUID.randomUUID();
    Attempt attempt = ledger.open(order, UUID.randomUUID(), 1500, "EUR", "tok_success");
    SANDBOX.fault("CHARGE_THEN_STALL");
    ChargeOutcome lost = provider.charge(attempt.id(), 1500, "EUR", "tok_success");
    assertThat(lost).isInstanceOf(ChargeOutcome.Unknown.class);
    payments.record(attempt.id(), lost);
    assertThat(ledger.find(attempt.id()).status()).isEqualTo("UNKNOWN");
    SANDBOX.fault("NONE");
    double before = reconciled("SUCCEEDED");

    due(attempt.id());
    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.RESOLVED);

    Attempt after = ledger.find(attempt.id());
    assertThat(after.status()).isEqualTo("SUCCEEDED");
    assertThat(after.providerChargeId()).startsWith("ch_");
    assertThat(after.nextReconcileAt()).isNull();
    assertThat(events(order, "PaymentSucceeded")).hasSize(1);
    assertThat(SANDBOX.charges(attempt.id())).isEqualTo(1);
    assertThat(reconciled("SUCCEEDED")).isEqualTo(before + 1);
  }

  /** The ARCHITECTURE E.4 case: payment-service died after the charge, before recording it. */
  @Test
  void anInitiatedAttemptLeftByACrashIsResolvedFromTheProvider() {
    UUID order = UUID.randomUUID();
    Attempt attempt = ledger.open(order, UUID.randomUUID(), 900, "EUR", "tok_success");
    provider.charge(attempt.id(), 900, "EUR", "tok_success");
    // ... the process dies here. A live attempt is not touched; only one older than any call.
    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.SKIPPED);
    age(attempt.id(), "updated_at", "1 minute");

    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.RESOLVED);

    assertThat(ledger.find(attempt.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(SANDBOX.charges(attempt.id())).isEqualTo(1);
  }

  @Test
  void aDeclineFoundAtTheProviderIsRecordedAsADecline() throws Exception {
    UUID order = UUID.randomUUID();
    Attempt attempt = ledger.open(order, UUID.randomUUID(), 400, "EUR", "tok_decline");
    provider.charge(attempt.id(), 400, "EUR", "tok_decline");
    age(attempt.id(), "updated_at", "1 minute");

    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.RESOLVED);

    assertThat(ledger.find(attempt.id()).status()).isEqualTo("FAILED");
    assertThat(events(order, "PaymentFailed").getFirst().path("payload").path("reason").asString())
        .isEqualTo("DECLINED");
  }

  /**
   * No charge, ever: waits out the window, then fails so the saga can release the stock. A charge
   * that still lands afterwards is refunded on the follow-up look (INV-5).
   */
  @Test
  void noChargeForTheWholeWindowFailsAndALateChargeIsThenRefunded() {
    UUID order = UUID.randomUUID();
    Attempt attempt = unknown(order, 2100);

    due(attempt.id());
    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.STILL_UNKNOWN);
    Attempt waiting = ledger.find(attempt.id());
    assertThat(waiting.status()).isEqualTo("UNKNOWN");
    assertThat(waiting.reconcileAttempts()).isEqualTo(1);
    assertThat(waiting.nextReconcileAt()).isAfter(OffsetDateTime.now());

    age(attempt.id(), "created_at", "16 minutes");
    due(attempt.id());
    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.UNRESOLVED);
    Attempt failed = ledger.find(attempt.id());
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.failureCode()).isEqualTo("timeout_unresolved");
    assertThat(failed.nextReconcileAt()).as("one more look is booked").isNotNull();
    assertThat(events(order, "PaymentFailed").getFirst().path("payload").path("reason").asString())
        .isEqualTo("TIMEOUT_UNRESOLVED");

    // The charge request was still on its way after all.
    provider.charge(attempt.id(), 2100, "EUR", "tok_success");
    due(attempt.id());
    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.LATE_CHARGE_REFUNDED);
    assertThat(ledger.find(attempt.id()).status()).isEqualTo("REFUNDED");
    assertThat(SANDBOX.refundedMinor(attempt.id())).isEqualTo(2100);
    assertThat(events(order, "PaymentRefunded")).hasSize(1);
  }

  /**
   * ADR-0011's open gap: the cancel found no charge and closed the attempt, but a charge request
   * was in flight, and the process died before its answer could be recorded and refunded.
   */
  @Test
  void aChargeThatLandedAfterACancelAndACrashIsRefunded() {
    UUID order = UUID.randomUUID();
    Attempt attempt = ledger.open(order, UUID.randomUUID(), 1700, "EUR", "tok_success");
    assertThat(ledger.cancelUnsettled(attempt.id())).isTrue();
    assertThat(ledger.find(attempt.id()).nextReconcileAt()).isNotNull();
    provider.charge(attempt.id(), 1700, "EUR", "tok_success");
    // ... no one records this answer.

    due(attempt.id());
    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.LATE_CHARGE_REFUNDED);

    assertThat(ledger.find(attempt.id()).status()).isEqualTo("REFUNDED");
    assertThat(SANDBOX.refundedMinor(attempt.id())).isEqualTo(1700);
  }

  @Test
  void aClosedAttemptWithNoChargeIsLookedAtOnceThenLeftAlone() {
    UUID order = UUID.randomUUID();
    Attempt attempt = ledger.open(order, UUID.randomUUID(), 300, "EUR", "tok_success");
    ledger.cancelUnsettled(attempt.id());

    due(attempt.id());
    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.CLOSED);

    assertThat(ledger.find(attempt.id()).nextReconcileAt()).isNull();
    assertThat(ledger.dueForReconciliation(10_000)).doesNotContain(attempt.id());
    assertThat(ledger.find(attempt.id()).status()).isEqualTo("CANCELLED");
  }

  /** A tombstone never reached the provider under any key: there is nothing to look for. */
  @Test
  void aTombstoneIsNeverLookedAt() {
    UUID order = UUID.randomUUID();
    payments.cancel(order);
    Attempt tombstone = ledger.findByOrder(order).orElseThrow();
    age(tombstone.id(), "updated_at", "1 hour");
    assertThat(ledger.dueForReconciliation(10_000)).doesNotContain(tombstone.id());
  }

  @Test
  void aProviderOutageLeavesTheAttemptUnknownAndBacksOff() throws Exception {
    UUID order = UUID.randomUUID();
    Attempt attempt = unknown(order, 800);
    SANDBOX.fault("FAIL_ALL");
    due(attempt.id());

    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.PROVIDER_UNAVAILABLE);

    Attempt after = ledger.find(attempt.id());
    assertThat(after.status()).isEqualTo("UNKNOWN");
    assertThat(after.reconcileAttempts()).isEqualTo(1);
    // Not due again straight away: the next look was booked before the provider was asked.
    assertThat(ledger.dueForReconciliation(10_000)).doesNotContain(attempt.id());
  }

  /** A refund whose call failed stays REFUND_PENDING; nothing but the reconciler retries it. */
  @Test
  void aPendingRefundWhoseCallFailedIsRetried() throws Exception {
    UUID order = UUID.randomUUID();
    Attempt attempt = ledger.open(order, UUID.randomUUID(), 650, "EUR", "tok_success");
    payments.record(attempt.id(), provider.charge(attempt.id(), 650, "EUR", "tok_success"));
    SANDBOX.fault("FAIL_ALL");
    try {
      payments.cancel(order);
    } catch (ProviderClient.ProviderUnavailableException expected) {
      // The refund call failed after the attempt was marked REFUND_PENDING.
    }
    SANDBOX.fault("NONE");
    assertThat(ledger.find(attempt.id()).status()).isEqualTo("REFUND_PENDING");
    age(attempt.id(), "updated_at", "1 minute");

    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.REFUNDED);

    assertThat(ledger.find(attempt.id()).status()).isEqualTo("REFUNDED");
    assertThat(SANDBOX.refundedMinor(attempt.id())).isEqualTo(650);
    assertThat(events(order, "PaymentRefunded")).hasSize(1);
  }

  /**
   * The gate's race: the reconciler and a late provider answer (or a redelivered command) both
   * resolve the same attempt. Whatever the interleaving, it is recorded once and announced once.
   */
  @Test
  void theReconcilerAndALateAnswerRacingResolveTheAttemptOnce() throws Exception {
    List<Attempt> attempts = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Attempt attempt = unknown(UUID.randomUUID(), 1000 + i);
      provider.charge(attempt.id(), 1000 + i, "EUR", "tok_success");
      due(attempt.id());
      attempts.add(attempt);
    }
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> running = new ArrayList<>();
      for (Attempt attempt : attempts) {
        String chargeId = provider.findCharge(attempt.id()).orElseThrow().id();
        running.add(pool.submit((Callable<Look>) () -> reconciler.reconcile(attempt.id())));
        running.add(
            pool.submit(
                () -> payments.record(attempt.id(), new ChargeOutcome.Succeeded(chargeId))));
      }
      for (Future<?> f : running) {
        f.get();
      }
    }
    for (Attempt attempt : attempts) {
      assertThat(ledger.find(attempt.id()).status()).isEqualTo("SUCCEEDED");
      assertThat(events(attempt.orderId(), "PaymentSucceeded")).hasSize(1);
      assertThat(SANDBOX.charges(attempt.id())).isEqualTo(1);
    }
  }

  @Test
  void aRoundReportsWhatItDidAndUpdatesTheBacklog() {
    Attempt open = unknown(UUID.randomUUID(), 500);
    due(open.id());

    var round = reconciler.reconcile();

    assertThat(round.get(Look.STILL_UNKNOWN)).isGreaterThanOrEqualTo(1);
    assertThat(meters.get("payment.reconciliation.open").gauge().value()).isGreaterThanOrEqualTo(1);
    assertThat(meters.get("payment.reconciliation.oldest.age").gauge().value()).isPositive();
  }

  // --- Helpers -------------------------------------------------------------------------------

  /** An attempt whose charge call timed out without reaching the provider. */
  Attempt unknown(UUID order, long amountMinor) {
    Attempt attempt = ledger.open(order, UUID.randomUUID(), amountMinor, "EUR", "tok_success");
    payments.record(attempt.id(), new ChargeOutcome.Unknown("PROVIDER_TIMEOUT"));
    assertThat(ledger.find(attempt.id()).status()).isEqualTo("UNKNOWN");
    return attempt;
  }

  void due(UUID attemptId) {
    jdbc.sql(
            "update payment_attempts set next_reconcile_at = now() - interval '1 second'"
                + " where id = ?")
        .param(attemptId)
        .update();
  }

  void age(UUID attemptId, String column, String by) {
    jdbc.sql(
            "update payment_attempts set "
                + column
                + " = "
                + column
                + " - ?::interval where id = ?")
        .params(by, attemptId)
        .update();
  }

  List<JsonNode> events(UUID order, String type) {
    return jdbc
        .sql(
            "select envelope from outbox_events where partition_key = ? and event_type = ?"
                + " order by seq")
        .params(order.toString(), type)
        .query(String.class)
        .list()
        .stream()
        .map(json::readTree)
        .toList();
  }

  double reconciled(String outcome) {
    var counter = meters.find("payment.reconciled").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }
}
