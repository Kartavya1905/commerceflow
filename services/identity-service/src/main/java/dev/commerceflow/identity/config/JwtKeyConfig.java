package dev.commerceflow.identity.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import dev.commerceflow.security.Jwts;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Loads the RSA signing key from a PKCS#8 PEM file (scripts/dev-up.sh generates it into the
 * git-ignored secrets/ folder) and exposes the encoder, the decoder, and the public JWK set.
 *
 * <p>The key id is the RFC 7638 thumbprint of the public key, so it changes automatically when the
 * key changes and services fetching the JWKS pick up the new key by its new id.
 */
@Configuration(proxyBeanMethods = false)
public class JwtKeyConfig {

  private static final int MIN_KEY_BITS = 2048;

  @Bean
  RSAKey signingKey(IdentityProperties properties) {
    try {
      String pem = Files.readString(properties.jwtPrivateKeyPath());
      RSAPrivateCrtKey privateKey = parsePkcs8(pem);
      if (privateKey.getModulus().bitLength() < MIN_KEY_BITS) {
        throw new IllegalStateException(
            "JWT signing key must be at least " + MIN_KEY_BITS + " bits");
      }
      RSAPublicKey publicKey =
          (RSAPublicKey)
              KeyFactory.getInstance("RSA")
                  .generatePublic(
                      new RSAPublicKeySpec(
                          privateKey.getModulus(), privateKey.getPublicExponent()));
      return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
    } catch (IOException e) {
      throw new IllegalStateException(
          "Cannot read the JWT private key at "
              + properties.jwtPrivateKeyPath()
              + ". Run scripts/dev-up.sh to generate it, or set JWT_PRIVATE_KEY_PATH.",
          e);
    } catch (GeneralSecurityException | JOSEException e) {
      throw new IllegalStateException("The JWT private key is not a valid PKCS#8 RSA key", e);
    }
  }

  @Bean
  JWKSet publicJwkSet(RSAKey signingKey) {
    return new JWKSet(signingKey.toPublicJWK());
  }

  @Bean
  JwtEncoder jwtEncoder(RSAKey signingKey) {
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
  }

  /** identity-service validates its own tokens with the key it holds; no HTTP call to itself. */
  @Bean
  JwtDecoder jwtDecoder(RSAKey signingKey) throws JOSEException {
    return Jwts.decoder(signingKey.toRSAPublicKey());
  }

  // PEM format markers that the parser looks for, not key material. The allow comments stop secret
  // scanners from flagging the parser itself.
  private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----"; // gitleaks:allow
  private static final String PEM_END = "-----END PRIVATE KEY-----"; // gitleaks:allow

  static RSAPrivateCrtKey parsePkcs8(String pem) throws GeneralSecurityException {
    if (!pem.contains(PEM_BEGIN)) {
      throw new GeneralSecurityException("expected a PKCS#8 'BEGIN PRIVATE KEY' PEM block");
    }
    String base64 = pem.replace(PEM_BEGIN, "").replace(PEM_END, "").replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(base64);
    return (RSAPrivateCrtKey)
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
  }
}
