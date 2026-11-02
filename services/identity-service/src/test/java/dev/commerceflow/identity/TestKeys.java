package dev.commerceflow.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Signing keys generated fresh for each test run; nothing secret is checked into the repo. */
public final class TestKeys {

  private static final KeyPair SERVICE_KEY = generate();
  private static final Path SERVICE_KEY_FILE = writePem(SERVICE_KEY);

  private TestKeys() {}

  /** The PKCS#8 PEM file identity-service loads in tests. */
  public static String privateKeyPath() {
    return SERVICE_KEY_FILE.toString();
  }

  /** An encoder holding a different key, for forging tokens the service must reject. */
  public static JwtEncoder attackerEncoder() {
    try {
      KeyPair attacker = generate();
      RSAKey key =
          new RSAKey.Builder((RSAPublicKey) attacker.getPublic())
              .privateKey((RSAPrivateKey) attacker.getPrivate())
              .keyIDFromThumbprint()
              .build();
      return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String pkcs8Pem(KeyPair pair) {
    String base64 =
        Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
    return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
  }

  private static Path writePem(KeyPair pair) {
    try {
      Path file = Files.createTempFile("identity-test-key", ".pem");
      file.toFile().deleteOnExit();
      Files.writeString(file, pkcs8Pem(pair));
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
