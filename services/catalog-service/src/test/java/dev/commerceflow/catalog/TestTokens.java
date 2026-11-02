package dev.commerceflow.catalog;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import dev.commerceflow.security.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Stands in for identity-service: a test key pair, a decoder that trusts it (with the production
 * issuer and audience rules), and helpers to mint tokens.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTokens {

  private static final KeyPair KEYS = generate();
  private static final JwtEncoder ENCODER = encoder(KEYS);

  public static final UUID ADMIN_ID = UUID.randomUUID();
  public static final UUID CUSTOMER_ID = UUID.randomUUID();

  @Bean
  @Primary
  JwtDecoder testJwtDecoder() {
    return Jwts.decoder((RSAPublicKey) KEYS.getPublic());
  }

  public static String admin() {
    return "Bearer " + mint(ADMIN_ID, "ADMIN");
  }

  public static String customer() {
    return "Bearer " + mint(CUSTOMER_ID, "CUSTOMER");
  }

  static String mint(UUID subject, String role) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(Jwts.ISSUER)
            .audience(List.of(Jwts.AUDIENCE))
            .subject(subject.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .claim(Jwts.ROLES_CLAIM, List.of(role))
            .build();
    return ENCODER
        .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
        .getTokenValue();
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JwtEncoder encoder(KeyPair pair) {
    RSAKey key =
        new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey((RSAPrivateKey) pair.getPrivate())
            .keyID("test")
            .build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
  }
}
