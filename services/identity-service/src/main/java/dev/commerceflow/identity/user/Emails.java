package dev.commerceflow.identity.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class Emails {

  private Emails() {}

  /** Trimmed and lower-cased, so Ada@Example.com and ada@example.com are one account. */
  public static String normalize(String email) {
    return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * A short SHA-256 fingerprint for audit records about an email that may not belong to any
   * account. Lets an operator correlate repeated attempts without storing the address itself.
   */
  public static String fingerprint(String email) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(normalize(email).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
