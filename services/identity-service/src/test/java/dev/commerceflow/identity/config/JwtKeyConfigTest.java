package dev.commerceflow.identity.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;

class JwtKeyConfigTest {

  /**
   * `openssl genrsa` on OpenSSL 1.x writes PKCS#1 ("BEGIN RSA PRIVATE KEY"). The service needs
   * PKCS#8, and must say so clearly instead of failing with a cryptic decoding error.
   */
  @Test
  void rejectsPkcs1PemWithAClearMessage() {
    String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n";

    assertThatThrownBy(() -> JwtKeyConfig.parsePkcs8(pkcs1))
        .isInstanceOf(GeneralSecurityException.class)
        .hasMessageContaining("PKCS#8");
  }
}
