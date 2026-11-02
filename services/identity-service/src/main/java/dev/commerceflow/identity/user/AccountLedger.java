package dev.commerceflow.identity.user;

import dev.commerceflow.identity.config.IdentityProperties;
import dev.commerceflow.identity.token.TokenService;
import dev.commerceflow.identity.token.TokenService.IssuedTokens;
import dev.commerceflow.web.ApiException;
import dev.commerceflow.web.audit.AuditWriter;
import dev.commerceflow.web.audit.AuditWriter.Outcome;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short database transactions behind registration and login. {@link AccountService} does the
 * slow Argon2 work first and only then calls in here, so no database connection is held while a
 * password is being hashed.
 */
@Component
class AccountLedger {

  private final UserRepository users;
  private final TokenService tokens;
  private final AuditWriter audit;
  private final IdentityProperties properties;

  AccountLedger(
      UserRepository users, TokenService tokens, AuditWriter audit, IdentityProperties properties) {
    this.users = users;
    this.tokens = tokens;
    this.audit = audit;
    this.properties = properties;
  }

  @Transactional
  public User createUser(
      String normalizedEmail, String passwordHash, String displayName, Role role, Instant now) {
    if (users.existsByEmail(normalizedEmail)) {
      throw emailTaken();
    }
    User user = User.create(normalizedEmail, passwordHash, displayName.strip(), role, now);
    try {
      users.saveAndFlush(user);
    } catch (DataIntegrityViolationException e) {
      // Two registrations for the same email raced past existsByEmail; the unique key decided.
      throw emailTaken();
    }
    audit.record(
        user.id(),
        role.name(),
        "USER_REGISTERED",
        "user",
        user.id().toString(),
        Outcome.SUCCESS,
        Map.of());
    return user;
  }

  @Transactional
  public void unknownEmail(String normalizedEmail) {
    audit.record(
        null,
        null,
        "LOGIN_FAILED",
        "user",
        null,
        Outcome.FAILURE,
        Map.of("reason", "unknown_email", "emailFingerprint", Emails.fingerprint(normalizedEmail)));
  }

  @Transactional
  public void loginBlocked(User user, Instant now) {
    audit.record(
        user.id(),
        user.role().name(),
        "LOGIN_FAILED",
        "user",
        user.id().toString(),
        Outcome.DENIED,
        Map.of("reason", user.isLocked(now) ? "locked" : "disabled"));
  }

  /** The counter is incremented atomically in SQL, so concurrent guesses are all counted. */
  @Transactional
  public void wrongPassword(User user, Instant now) {
    users.recordFailedLogin(
        user.id(), properties.maxFailedLogins(), now.plus(properties.lockoutDuration()), now);
    boolean nowLocked = user.failedLoginCount() + 1 >= properties.maxFailedLogins();
    audit.record(
        user.id(),
        user.role().name(),
        nowLocked ? "ACCOUNT_LOCKED" : "LOGIN_FAILED",
        "user",
        user.id().toString(),
        Outcome.FAILURE,
        Map.of("reason", "wrong_password"));
  }

  @Transactional
  public IssuedTokens loginSucceeded(User user, Instant now) {
    users.resetFailedLogins(user.id(), now);
    audit.record(
        user.id(),
        user.role().name(),
        "LOGIN_SUCCEEDED",
        "user",
        user.id().toString(),
        Outcome.SUCCESS,
        Map.of());
    return tokens.startSession(user);
  }

  static ApiException emailTaken() {
    return ApiException.conflict("email-taken", "An account with this email already exists.");
  }
}
