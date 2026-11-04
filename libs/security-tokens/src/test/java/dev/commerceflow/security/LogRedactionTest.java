package dev.commerceflow.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogRedactionTest {

  @Test
  void removesTokensWhereverTheyAppear() {
    String jwt =
        "eyJhbGciOiJSUzI1NiIsImtpZCI6ImsxIn0.eyJzdWIiOiIxMjMiLCJyb2xlcyI6WyJBRE1JTiJdfQ.c2lnbmF0dXJl";
    assertEquals("token [jwt] rejected", LogRedaction.redact("token " + jwt + " rejected"));
    assertEquals(
        "Authorization: Bearer [redacted]",
        LogRedaction.redact("Authorization: Bearer abc.def-123"));
    assertEquals(
        "{\"password\":\"[redacted]\", \"apiKey\": \"[redacted]\"}",
        LogRedaction.redact("{\"password\":\"hunter2!\", \"apiKey\": \"k-42\"}"));
    assertEquals("refresh_token=[redacted]&x=1", LogRedaction.redact("refresh_token=Zm9v&x=1"));
  }

  /** The case it exists for: a database constraint message quoting the value that broke it. */
  @Test
  void removesEmailAddressesFromExceptionMessages() {
    assertEquals(
        "duplicate key value violates unique constraint \"users_email_uk\" Detail: Key"
            + " (email)=([email]) already exists.",
        LogRedaction.redact(
            "duplicate key value violates unique constraint \"users_email_uk\" Detail: Key"
                + " (email)=(ada.lovelace+shop@example.co.uk) already exists."));
  }

  @Test
  void removesPaymentTokensAndCardNumbersButNotTimestampsOrIds() {
    assertEquals("charged tok_[redacted]", LogRedaction.redact("charged tok_success"));
    assertEquals("card [pan] declined", LogRedaction.redact("card 4111 1111 1111 1111 declined"));
    // A 13-digit epoch millisecond value fails the Luhn check and stays readable.
    String timestamp = "at 1793681163408 ms";
    assertEquals(timestamp, LogRedaction.redact(timestamp));
    String ids = "order 2f4c1a8e-77b3-4e0a-9d41-0c5b7e1f3a22 saga AWAITING_PAYMENT";
    assertEquals(ids, LogRedaction.redact(ids));
  }

  @Test
  void luhn() {
    assertTrue(LogRedaction.luhn("4111111111111111"));
    assertFalse(LogRedaction.luhn("4111111111111112"));
  }

  @Test
  void leavesOrdinaryTextAndNullAlone() {
    assertEquals(
        "Saga 42 re-sent AUTHORIZE_PAYMENT (1 of 5)",
        LogRedaction.redact("Saga 42 re-sent AUTHORIZE_PAYMENT (1 of 5)"));
    assertEquals(null, LogRedaction.redact(null));
  }
}
