package dev.commerceflow.identity.token;

import dev.commerceflow.identity.token.TokenService.IssuedTokens;
import dev.commerceflow.identity.user.User;
import dev.commerceflow.identity.user.UserRepository;
import dev.commerceflow.web.audit.AuditWriter;
import dev.commerceflow.web.audit.AuditWriter.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Refresh-token rotation, reuse detection, and logout. */
@Service
public class SessionService {

  private static final Logger log = LoggerFactory.getLogger(SessionService.class);

  private final RefreshTokenRepository refreshTokens;
  private final UserRepository users;
  private final TokenService tokens;
  private final AuditWriter audit;
  private final Clock clock;

  public SessionService(
      RefreshTokenRepository refreshTokens,
      UserRepository users,
      TokenService tokens,
      AuditWriter audit,
      Clock clock) {
    this.refreshTokens = refreshTokens;
    this.users = users;
    this.tokens = tokens;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Exchanges a refresh token for a new pair and retires the old one.
   *
   * <p>Returns empty instead of throwing on every failure. That matters for reuse: the family
   * revocation must commit even though the caller answers 401, and an exception here would roll it
   * back.
   */
  @Transactional
  public Optional<IssuedTokens> refresh(String rawToken) {
    Instant now = clock.instant();
    Optional<RefreshToken> found = refreshTokens.findForUpdateByHash(TokenService.hash(rawToken));
    if (found.isEmpty()) {
      return Optional.empty();
    }
    RefreshToken token = found.get();

    if (token.wasRetired()) {
      // An already-rotated token came back: either it was stolen or the legitimate client is
      // replaying it. Either way, end the whole session so the thief's copy dies too.
      int revoked = refreshTokens.revokeFamily(token.familyId(), now);
      audit.record(
          token.userId(),
          null,
          "REFRESH_TOKEN_REUSE_DETECTED",
          "session",
          token.familyId().toString(),
          Outcome.DENIED,
          Map.of("revokedTokens", revoked));
      log.warn("Refresh token reuse detected; session {} revoked", token.familyId());
      return Optional.empty();
    }
    if (!token.isUsable(now)) {
      return Optional.empty();
    }

    Optional<User> user = users.findById(token.userId()).filter(u -> u.canLogIn(now));
    if (user.isEmpty()) {
      refreshTokens.revokeFamily(token.familyId(), now);
      return Optional.empty();
    }

    IssuedTokens next = tokens.issue(user.get(), token.familyId());
    token.rotateTo(next.refreshTokenId(), now);
    return Optional.of(next);
  }

  @Transactional
  public void logout(UUID userId, UUID familyId) {
    int revoked = refreshTokens.revokeFamily(familyId, clock.instant());
    audit.record(
        userId,
        null,
        "LOGOUT",
        "session",
        familyId.toString(),
        Outcome.SUCCESS,
        Map.of("revokedTokens", revoked));
  }

  /** Expired tokens are useless; keep a day of history for investigating reuse alerts. */
  @Scheduled(cron = "0 17 3 * * *")
  @Transactional
  public void deleteExpiredTokens() {
    int deleted = refreshTokens.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
    log.info("Deleted {} expired refresh tokens", deleted);
  }
}
