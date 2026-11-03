package dev.commerceflow.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.commerceflow.payment.PaymentTestBase;
import dev.commerceflow.payment.payment.PaymentReconciler.Look;
import dev.commerceflow.payment.provider.ProviderClient;
import dev.commerceflow.payment.provider.ProviderClient.ChargeOutcome;
import dev.commerceflow.testsupport.TestTokens;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POST /api/v1/admin/payments/{attemptId}/reconcile: brings the next look forward to the
 * reconciler's next round, which this test runs itself (the scheduled one is slowed to hourly).
 */
class ReconcileNowIT extends PaymentTestBase {

  @Autowired MockMvc mvc;
  @Autowired PaymentLedger ledger;
  @Autowired PaymentService payments;
  @Autowired PaymentReconciler reconciler;
  @Autowired ProviderClient provider;
  @Autowired JdbcClient jdbc;

  @Test
  void anUnknownPaymentIsLookedAtInTheNextRoundInsteadOfWaiting() throws Exception {
    Attempt attempt = unknownButCharged(1900);
    // Its next look is 30 s away; without the request this round would not touch it.
    assertThat(ledger.find(attempt.id()).nextReconcileAt()).isAfter(OffsetDateTime.now());
    assertThat(ledger.dueForReconciliation(10_000)).doesNotContain(attempt.id());

    reconcile(attempt.id(), TestTokens.admin()).andExpect(status().isAccepted());

    assertThat(ledger.dueForReconciliation(10_000)).contains(attempt.id());
    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.RESOLVED);
    assertThat(ledger.find(attempt.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(SANDBOX.charges(attempt.id())).isEqualTo(1);
    assertThat(audit(attempt.id())).containsEntry("outcome", "SUCCESS");
  }

  /** Asking again, or while a round is already looking, still resolves the attempt once. */
  @Test
  void askingTwiceResolvesItOnce() throws Exception {
    Attempt attempt = unknownButCharged(2300);
    reconcile(attempt.id(), TestTokens.admin()).andExpect(status().isAccepted());
    reconcile(attempt.id(), TestTokens.admin()).andExpect(status().isAccepted());

    reconciler.reconcile();
    reconciler.reconcile();

    assertThat(ledger.find(attempt.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.sql(
                    "select count(*) from outbox_events where partition_key = ?"
                        + " and event_type = 'PaymentSucceeded'")
                .param(attempt.orderId().toString())
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void aSettledPaymentHasNothingLeftToReconcile() throws Exception {
    UUID order = UUID.randomUUID();
    payments.authorize(order, UUID.randomUUID(), 700, "EUR", "tok_success");
    Attempt settled = ledger.findByOrder(order).orElseThrow();
    assertThat(settled.status()).isEqualTo("SUCCEEDED");

    reconcile(settled.id(), TestTokens.admin())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("attempt-final")));

    assertThat(ledger.find(settled.id()).nextReconcileAt()).isNull();
    // Refused requests are audited too.
    assertThat(audit(settled.id())).containsEntry("outcome", "FAILURE");
  }

  /** A cancelled attempt still due its one later look can have it now; one that had it cannot. */
  @Test
  void aClosedAttemptCanBeLookedAtOnlyWhileItsLastLookIsPending() throws Exception {
    Attempt attempt = ledger.open(UUID.randomUUID(), UUID.randomUUID(), 450, "EUR", "tok_success");
    ledger.cancelUnsettled(attempt.id());
    reconcile(attempt.id(), TestTokens.admin()).andExpect(status().isAccepted());

    assertThat(reconciler.reconcile(attempt.id())).isEqualTo(Look.CLOSED);
    reconcile(attempt.id(), TestTokens.admin()).andExpect(status().isConflict());
  }

  @Test
  void onlyAdminsMayAskAndOnlyForAttemptsThatExist() throws Exception {
    Attempt attempt = unknownButCharged(300);
    reconcile(UUID.randomUUID(), TestTokens.admin()).andExpect(status().isNotFound());
    reconcile(attempt.id(), TestTokens.customer()).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/admin/payments/" + attempt.id() + "/reconcile"))
        .andExpect(status().isUnauthorized());
    // The customer's attempt changed nothing.
    assertThat(ledger.dueForReconciliation(10_000)).doesNotContain(attempt.id());
  }

  // --- Helpers -------------------------------------------------------------------------------

  /** Charged at the provider, but the answer was lost: UNKNOWN, next look in 30 s. */
  Attempt unknownButCharged(long amountMinor) {
    Attempt attempt =
        ledger.open(UUID.randomUUID(), UUID.randomUUID(), amountMinor, "EUR", "tok_success");
    provider.charge(attempt.id(), amountMinor, "EUR", "tok_success");
    payments.record(attempt.id(), new ChargeOutcome.Unknown("PROVIDER_TIMEOUT"));
    assertThat(ledger.find(attempt.id()).status()).isEqualTo("UNKNOWN");
    return attempt;
  }

  org.springframework.test.web.servlet.ResultActions reconcile(UUID attemptId, String token)
      throws Exception {
    return mvc.perform(
        post("/api/v1/admin/payments/" + attemptId + "/reconcile").header("Authorization", token));
  }

  Map<String, Object> audit(UUID attemptId) {
    return jdbc.sql(
            """
            select outcome, actor_role, details::text as details from audit_events
             where action = 'PAYMENT_RECONCILE_REQUESTED' and resource_id = ?
             order by id desc limit 1
            """)
        .param(attemptId.toString())
        .query()
        .singleRow();
  }
}
