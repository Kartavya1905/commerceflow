package dev.commerceflow.identity.user;

import dev.commerceflow.web.ApiException;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Rules beyond the length limits already enforced by request validation (12 to 128 characters,
 * following NIST SP 800-63B: length matters more than composition rules).
 */
public final class PasswordPolicy {

  private PasswordPolicy() {}

  public static void check(String password, String normalizedEmail) {
    String lower = password.toLowerCase(Locale.ROOT);
    String localPart = normalizedEmail.substring(0, Math.max(0, normalizedEmail.indexOf('@')));
    if (localPart.length() >= 3 && lower.contains(localPart)) {
      throw weak("The password must not contain your email name.");
    }
    if (password.chars().distinct().count() < 4) {
      throw weak("The password must use at least 4 different characters.");
    }
  }

  private static ApiException weak(String detail) {
    return new ApiException(HttpStatus.BAD_REQUEST, "weak-password", "Weak password", detail);
  }
}
