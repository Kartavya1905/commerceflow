package dev.commerceflow.identity.token;

import com.nimbusds.jose.jwk.RSAKey;
import dev.commerceflow.identity.config.IdentityProperties;
import dev.commerceflow.identity.user.User;
import dev.commerceflow.security.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues access tokens (RS256 JWT) and refresh tokens (random, stored hashed). */
@Service
public class TokenService {

  /** Claim carrying the refresh-token family, so logout can end exactly this session. */
  public static final String SESSION_CLAIM = "sid";

  public record IssuedTokens(
      String accessToken, long expiresInSeconds, String refreshToken, UUID refreshTokenId) {}

  private static final int REFRESH_TOKEN_BYTES = 32;

  private final JwtEncoder encoder;
  private final RSAKey signingKey;
  private final RefreshTokenRepository refreshTokens;
  private final IdentityProperties properties;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public TokenService(
      JwtEncoder encoder,
      RSAKey signingKey,
      RefreshTokenRepository refreshTokens,
      IdentityProperties properties,
      Clock clock) {
    this.encoder = encoder;
    this.signingKey = signingKey;
    this.refreshTokens = refreshTokens;
    this.properties = properties;
    this.clock = clock;
  }

  /** Starts a new session (a new refresh-token family). Must run inside a transaction. */
  public IssuedTokens startSession(User user) {
    return issue(user, UUID.randomUUID());
  }

  /** Issues the next token pair in an existing family. Must run inside a transaction. */
  IssuedTokens issue(User user, UUID familyId) {
    Instant now = clock.instant();

    String rawRefresh = newRefreshToken();
    RefreshToken row =
        RefreshToken.issue(
            user.id(), hash(rawRefresh), familyId, now, now.plus(properties.refreshTokenTtl()));
    refreshTokens.save(row);

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(Jwts.ISSUER)
            .audience(List.of(Jwts.AUDIENCE))
            .subject(user.id().toString())
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.accessTokenTtl()))
            .claim(Jwts.ROLES_CLAIM, List.of(user.role().name()))
            .claim(SESSION_CLAIM, familyId.toString())
            .build();
    JwsHeader header =
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build();
    String access = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

    return new IssuedTokens(access, properties.accessTokenTtl().toSeconds(), rawRefresh, row.id());
  }

  private String newRefreshToken() {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** SHA-256 is enough here: the token is 256 random bits, so it cannot be guessed offline. */
  public static byte[] hash(String rawToken) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
