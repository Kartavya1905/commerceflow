package dev.commerceflow.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.commerceflow.payment.PaymentTestBase;
import dev.commerceflow.payment.payment.PaymentLedger.Settlement;
import dev.commerceflow.payment.provider.ProviderClient;
import dev.commerceflow.payment.provider.ProviderClient.ChargeOutcome;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The race between "the saga gave up and the cancel found no charge" and "the charge was still in
 * flight and went through". Driven step by step, since the real interleaving needs two consumers.
 */
class LateChargeIT extends PaymentTestBase {

  @Autowired PaymentLedger ledger;
  @Autowired PaymentService payments;
  @Autowired ProviderClient provider;

  @Test
  void aChargeThatLandsAfterTheCancelIsRefundedAutomatically() {
    UUID order = UUID.randomUUID();
    Attempt attempt = ledger.open(order, UUID.randomUUID(), 2200, "EUR", "tok_success");

    // The cancel asks the provider first, finds nothing, and cancels the attempt.
    assertThat(provider.findCharge(attempt.id())).isEmpty();
    assertThat(ledger.cancelUnsettled(attempt.id())).isTrue();

    // Then the in-flight charge reaches the provider and its answer comes back.
    ChargeOutcome outcome = provider.charge(attempt.id(), 2200, "EUR", "tok_success");
    assertThat(outcome).isInstanceOf(ChargeOutcome.Succeeded.class);
    payments.record(attempt.id(), outcome);

    Attempt after = ledger.find(attempt.id());
    assertThat(after.status()).isEqualTo("REFUNDED");
    assertThat(after.providerChargeId()).isEqualTo(((ChargeOutcome.Succeeded) outcome).chargeId());
    assertThat(SANDBOX.refundedMinor(attempt.id())).isEqualTo(2200);
  }

  @Test
  void aLateDeclineOnACancelledAttemptChangesNothing() {
    UUID order = UUID.randomUUID();
    Attempt attempt = ledger.open(order, UUID.randomUUID(), 700, "EUR", "tok_decline");
    assertThat(ledger.cancelUnsettled(attempt.id())).isTrue();
    assertThat(ledger.settle(attempt.id(), new ChargeOutcome.Declined("card_declined")))
        .isEqualTo(Settlement.SUPERSEDED);
    assertThat(ledger.find(attempt.id()).status()).isEqualTo("CANCELLED");
  }

  @Test
  void aDuplicateCommandArrivingWhileTheFirstIsUnsettledResendsUnderTheSameKey() {
    UUID order = UUID.randomUUID();
    // A crash after TX1: the attempt exists, the provider was never asked.
    Attempt attempt = ledger.open(order, UUID.randomUUID(), 1300, "EUR", "tok_success");
    // The redelivered command finds it unsettled and sends the charge itself.
    payments.authorize(order, attempt.customerId(), 1300, "EUR", "tok_success");
    payments.authorize(order, attempt.customerId(), 1300, "EUR", "tok_success");

    assertThat(ledger.find(attempt.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(SANDBOX.charges(attempt.id())).isEqualTo(1);
  }

  /**
   * A crash after the provider charged but before TX2 recorded it. The redelivered command must
   * find that same charge, which works only because the key is the attempt id and not a fresh one
   * per call.
   */
  @Test
  void aCrashAfterTheChargeIsHealedByRedeliveryWithoutASecondCharge() {
    UUID order = UUID.randomUUID();
    Attempt attempt = ledger.open(order, UUID.randomUUID(), 1800, "EUR", "tok_success");
    ChargeOutcome lost = provider.charge(attempt.id(), 1800, "EUR", "tok_success");
    assertThat(lost).isInstanceOf(ChargeOutcome.Succeeded.class);
    // ... the process dies here; on restart the command is delivered again.

    payments.authorize(order, attempt.customerId(), 1800, "EUR", "tok_success");

    Attempt after = ledger.find(attempt.id());
    assertThat(after.status()).isEqualTo("SUCCEEDED");
    assertThat(after.providerChargeId()).isEqualTo(((ChargeOutcome.Succeeded) lost).chargeId());
    assertThat(SANDBOX.charges(attempt.id())).isEqualTo(1);
  }
}
