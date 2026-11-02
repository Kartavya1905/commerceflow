package dev.commerceflow.identity.user;

import dev.commerceflow.identity.token.TokenService.IssuedTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Registration and login.
 *
 * <p>Deliberately not transactional. Argon2 costs tens of milliseconds of CPU per call; doing it
 * inside a transaction would hold a pooled database connection the whole time, and a burst of
 * logins would starve every other query in the service. Hashing happens here, then {@link
 * AccountLedger} records the outcome in a short transaction.
 */
@Service
public class AccountService {

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final AccountLedger ledger;
  private final Clock clock;

  /**
   * Compared against when the email is unknown, so "no such user" costs the same Argon2 work as
   * "wrong password" and response time does not reveal which accounts exist.
   */
  private final String dummyHash;

  public AccountService(
      UserRepository users, PasswordEncoder passwordEncoder, AccountLedger ledger, Clock clock) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.ledger = ledger;
    this.clock = clock;
    this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
  }

  public User register(String email, String password, String displayName, Role role) {
    String normalized = Emails.normalize(email);
    PasswordPolicy.check(password, normalized);
    // Cheap early answer for the common duplicate case, before paying for a hash. The unique key
    // inside createUser still decides races.
    if (users.existsByEmail(normalized)) {
      throw AccountLedger.emailTaken();
    }
    String hash = passwordEncoder.encode(password);
    return ledger.createUser(normalized, hash, displayName, role, clock.instant());
  }

  /**
   * Returns tokens on success, empty on any failure. Failures are not exceptions because the
   * failed-attempt counter and the audit row must commit even though the caller answers 401.
   */
  public Optional<IssuedTokens> login(String email, String password) {
    Instant now = clock.instant();
    String normalized = Emails.normalize(email);
    Optional<User> found = users.findByEmail(normalized);

    if (found.isEmpty()) {
      passwordEncoder.matches(password, dummyHash);
      ledger.unknownEmail(normalized);
      return Optional.empty();
    }

    User user = found.get();
    boolean passwordOk = passwordEncoder.matches(password, user.passwordHash());

    if (!user.canLogIn(now)) {
      ledger.loginBlocked(user, now);
      return Optional.empty();
    }
    if (!passwordOk) {
      ledger.wrongPassword(user, now);
      return Optional.empty();
    }
    return Optional.of(ledger.loginSucceeded(user, now));
  }
}
