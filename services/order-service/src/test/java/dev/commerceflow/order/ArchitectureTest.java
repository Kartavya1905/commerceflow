package dev.commerceflow.order;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import dev.commerceflow.testsupport.ArchitectureRules;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The shared architecture rules (ArchitectureRules), over this service's production classes. */
class ArchitectureTest {

  static final String ROOT = "dev.commerceflow.order";
  static final JavaClasses CLASSES = ArchitectureRules.productionClasses(ROOT);

  static Stream<ArchRule> rules() {
    return ArchitectureRules.all(ROOT).stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rules")
  void holds(ArchRule rule) {
    rule.check(CLASSES);
  }
}
