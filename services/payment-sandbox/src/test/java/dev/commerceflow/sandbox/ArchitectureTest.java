package dev.commerceflow.sandbox;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import dev.commerceflow.testsupport.ArchitectureRules;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The shared architecture rules (ArchitectureRules), over this service's production classes. */
class ArchitectureTest {

  static final String ROOT = "dev.commerceflow.sandbox";
  static final JavaClasses CLASSES = ArchitectureRules.productionClasses(ROOT);

  static Stream<ArchRule> rules() {
    return ArchitectureRules.all(ROOT).stream()
        // The sandbox stands in for an external card provider: a fake, not part of the system,
        // and its one controller is its whole implementation.
        .filter(
            r ->
                !r.getDescription()
                    .equals(ArchitectureRules.controllersDoNotTouchPersistence().getDescription()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rules")
  void holds(ArchRule rule) {
    rule.check(CLASSES);
  }
}
