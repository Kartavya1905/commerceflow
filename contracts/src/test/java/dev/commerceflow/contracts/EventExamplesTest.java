package dev.commerceflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Valid examples pass, and each invalid example fails for the reason recorded in
 * examples/invalid/expectations.json, so a test cannot pass by rejecting a message for the wrong
 * reason.
 */
class EventExamplesTest {

  private static final EventContracts CONTRACTS = EventContracts.load();
  private static final Map<String, String> EXPECTED_VIOLATIONS =
      JsonMapper.builder()
          .build()
          .readValue(
              ContractResources.read(
                  ContractResources.resource("examples/invalid/expectations.json")),
              new TypeReference<Map<String, String>>() {});

  static List<Path> validExamples() {
    return ContractResources.validExamples();
  }

  static List<Path> invalidExamples() {
    return ContractResources.invalidExamples();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("validExamples")
  void validExampleHasNoViolations(Path example) {
    assertThat(CONTRACTS.validate(ContractResources.read(example))).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidExamples")
  void invalidExampleIsRejectedForTheExpectedReason(Path example) {
    String fileName = example.getFileName().toString();
    String expected = EXPECTED_VIOLATIONS.get(fileName);

    List<String> violations = CONTRACTS.validate(ContractResources.read(example));

    assertThat(expected).as("expectations.json entry for %s", fileName).isNotBlank();
    assertThat(violations).as("violations for %s", fileName).anyMatch(v -> v.contains(expected));
  }

  @Test
  void everyExpectationPointsAtAnExistingFile() {
    List<String> files = invalidExamples().stream().map(p -> p.getFileName().toString()).toList();

    assertThat(EXPECTED_VIOLATIONS.keySet()).containsExactlyInAnyOrderElementsOf(files);
  }

  @Test
  void rejectsMessagesThatAreNotJson() {
    assertThat(CONTRACTS.validate("not json"))
        .singleElement()
        .asString()
        .contains("not valid JSON");
  }

  @Test
  void rejectsUnknownEventTypes() {
    assertThat(CONTRACTS.validate("{\"eventType\":\"OrderTeleported\"}"))
        .singleElement()
        .asString()
        .contains("unknown event type");
  }
}
