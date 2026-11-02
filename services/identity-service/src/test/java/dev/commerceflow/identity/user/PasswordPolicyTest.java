package dev.commerceflow.identity.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.commerceflow.web.ApiException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  @Test
  void acceptsALongPassphrase() {
    assertThatCode(() -> PasswordPolicy.check("correct horse battery staple", "ada@example.com"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsPasswordsContainingTheEmailNameInAnyCase() {
    assertThatThrownBy(() -> PasswordPolicy.check("MyLovelace2026!", "lovelace@example.com"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("email name");
  }

  @Test
  void ignoresVeryShortEmailNames() {
    assertThatCode(() -> PasswordPolicy.check("a-perfectly-fine-password", "ab@example.com"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsRepetitivePasswords() {
    assertThatThrownBy(() -> PasswordPolicy.check("aaaaaaaaaaaabbb", "ada@example.com"))
        .isInstanceOf(ApiException.class);
  }
}
