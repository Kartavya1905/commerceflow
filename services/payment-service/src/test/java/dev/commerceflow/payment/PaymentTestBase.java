package dev.commerceflow.payment;

import dev.commerceflow.testsupport.Containers;
import dev.commerceflow.testsupport.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * One application context for every payment test (real sandbox process, Kafka, Postgres), shared
 * because the configuration is declared once, here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({Containers.Postgres.class, Containers.Kafka.class, TestTokens.class})
public abstract class PaymentTestBase {

  protected static final SandboxProcess SANDBOX = SandboxProcess.get();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("commerceflow.payment.provider-uri", () -> SANDBOX.baseUrl);
    registry.add("commerceflow.payment.provider-api-key", () -> SandboxProcess.API_KEY);
    registry.add("commerceflow.payment.provider-read-timeout", () -> "1s");
    // Timeouts are provoked on purpose here; the breaker must not open and skew later tests.
    registry.add("commerceflow.payment.breaker-minimum-calls", () -> "1000");
    // Tests run reconciliation rounds themselves, so the scheduled one must not get there first.
    registry.add("commerceflow.payment.reconcile-interval", () -> "1h");
  }

  @AfterEach
  void resetSandbox() throws Exception {
    SANDBOX.fault("NONE");
  }
}
