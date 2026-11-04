package dev.commerceflow.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The service's real logging, in the JSON format Compose runs it with, through the customizer its
 * application.yml configures: an email and a token that reach a log line, here inside an exception
 * message as they would in practice, come out masked (INV-8).
 */
@SpringBootTest(properties = "logging.structured.format.console=ecs")
@Import(PostgresTestcontainer.class)
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionIT {

  private static final Logger log = LoggerFactory.getLogger(LogRedactionIT.class);

  /**
   * Logback is configured once per JVM: a test context that started earlier with plain-text logging
   * would otherwise decide the format here too. A reset makes Spring Boot configure it again, from
   * this test's properties, when this context starts.
   */
  @BeforeAll
  static void freshLogging() {
    ((ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory()).reset();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("commerceflow.identity.jwt-private-key-path", TestKeys::privateKeyPath);
  }

  @Test
  void emailsAndTokensAreMaskedInTheJsonLog(CapturedOutput output) {
    String email = "ada.lovelace@example.com";
    String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhZGEifQ.c2lnbmF0dXJlLXZhbHVl";
    log.error(
        "Registration failed for {}",
        email,
        new IllegalStateException("Key (email)=(" + email + ") already exists; Bearer " + jwt));

    String logged = output.getOut();
    assertThat(logged).contains("Registration failed for [email]", "Bearer [jwt]");
    assertThat(logged).doesNotContain(email, jwt);
    // It is the structured format, with the stack trace also redacted, not plain text.
    assertThat(logged)
        .contains(
            "\"level\":\"ERROR\"",
            "\"stack_trace\":\"java.lang.IllegalStateException: Key (email)=([email])");
  }
}
