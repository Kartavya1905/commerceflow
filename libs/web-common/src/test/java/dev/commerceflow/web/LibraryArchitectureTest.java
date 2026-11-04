package dev.commerceflow.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.junit.jupiter.api.Test;

/** A shared library serves every service and must not know any of them. */
class LibraryArchitectureTest {

  static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("dev.commerceflow.web");

  @Test
  void dependsOnNoService() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "dev.commerceflow.identity..",
            "dev.commerceflow.catalog..",
            "dev.commerceflow.order..",
            "dev.commerceflow.inventory..",
            "dev.commerceflow.payment..",
            "dev.commerceflow.sandbox..",
            "dev.commerceflow.gateway..")
        .check(CLASSES);
  }

  @Test
  void followsTheGeneralCodingRules() {
    GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(CLASSES);
    GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(CLASSES);
    GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(CLASSES);
  }
}
