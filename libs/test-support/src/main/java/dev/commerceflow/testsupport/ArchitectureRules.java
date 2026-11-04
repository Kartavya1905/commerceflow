package dev.commerceflow.testsupport;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.List;

/**
 * The architecture rules every service is held to (ROADMAP Milestone 9), as ArchUnit rules over the
 * service's production classes. Each service's ArchitectureTest runs {@link #all}; a rule that
 * fails names the class and the dependency.
 */
public final class ArchitectureRules {

  /** Root packages of every deployable; a service may use the libraries, never another service. */
  public static final List<String> SERVICES =
      List.of(
          "dev.commerceflow.identity",
          "dev.commerceflow.catalog",
          "dev.commerceflow.order",
          "dev.commerceflow.inventory",
          "dev.commerceflow.payment",
          "dev.commerceflow.sandbox",
          "dev.commerceflow.gateway");

  private ArchitectureRules() {}

  /** Production classes only: tests may reach anywhere. */
  public static JavaClasses productionClasses(String rootPackage) {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
        .importPackages(rootPackage);
  }

  public static List<ArchRule> all(String rootPackage) {
    return List.of(
        noOtherService(rootPackage),
        controllersDoNotTouchPersistence(),
        messagesLeaveOnlyThroughTheOutbox(),
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION,
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS,
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING);
  }

  /**
   * Each service owns its data and talks to others only over HTTP or Kafka (ADR-0006). Separate
   * modules already make a compile-time import impossible; this also catches a copy-paste of a
   * class into the wrong package.
   */
  public static ArchRule noOtherService(String rootPackage) {
    String[] others =
        SERVICES.stream()
            .filter(s -> !s.equals(rootPackage))
            .map(s -> s + "..")
            .toArray(String[]::new);
    return noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(others)
        .because("a service depends on the shared libraries, never on another service");
  }

  /**
   * Controllers translate HTTP; queries and changes live behind them. A controller holding a
   * JdbcClient or a repository is where owner checks (INV-7) and transactions get skipped.
   */
  public static ArchRule controllersDoNotTouchPersistence() {
    return noClasses()
        .that()
        .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("Repository")
        .because("controllers call services and query classes, which own SQL and transactions")
        // The gateway has no controllers at all, which satisfies the rule.
        .allowEmptyShould(true);
  }

  /**
   * Events are written to the outbox in the business transaction (ADR-0004); sending to Kafka
   * directly from a service would publish changes that may still roll back.
   */
  public static ArchRule messagesLeaveOnlyThroughTheOutbox() {
    return noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.apache.kafka.clients.producer.KafkaProducer")
        .because("services publish through OutboxWriter, in the transaction that made the change");
  }
}
