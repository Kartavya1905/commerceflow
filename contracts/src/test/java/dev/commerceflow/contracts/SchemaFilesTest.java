package dev.commerceflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every schema file is a well-formed draft 2020-12 schema that follows the project's rules. */
class SchemaFilesTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Schema META_SCHEMA =
      SchemaRegistry.withDialect(Dialects.getDraft202012())
          .getSchema(SchemaLocation.of(Dialects.getDraft202012().getId()));

  static List<Path> schemaFiles() {
    return ContractResources.schemaFiles();
  }

  static List<Path> eventSchemaFiles() {
    return ContractResources.eventSchemaFiles();
  }

  @Test
  void envelopeAndAllNineteenEventSchemasArePresent() {
    assertThat(ContractResources.eventSchemaFiles()).hasSize(19);
    assertThat(schemaFiles())
        .extracting(ContractResources::typeOf)
        .contains(ContractResources.ENVELOPE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("schemaFiles")
  void isValidAgainstTheDraft202012MetaSchema(Path file) {
    var errors = META_SCHEMA.validate(ContractResources.read(file), InputFormat.JSON);

    assertThat(errors).as("meta-schema errors in %s", file.getFileName()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("schemaFiles")
  void idMatchesFileName(Path file) {
    JsonNode schema = MAPPER.readTree(ContractResources.read(file));

    assertThat(schema.path("$id").asString())
        .isEqualTo(EventContracts.SCHEMA_BASE_URI + file.getFileName());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("eventSchemaFiles")
  void pinsEventTypeAndVersionToTheFileName(Path file) {
    JsonNode properties = MAPPER.readTree(ContractResources.read(file)).path("properties");

    assertThat(properties.path("eventType").path("const").asString())
        .isEqualTo(ContractResources.typeOf(file));
    assertThat(properties.path("eventVersion").path("const").asInt()).isEqualTo(1);
  }

  /**
   * Producer schemas are strict so that a producer cannot leak an unexpected field (for example a
   * card number). Any object schema that declares properties must forbid additional ones. The root
   * of an event schema is exempt because it inherits strictness from the envelope.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("schemaFiles")
  void objectSchemasForbidUnknownProperties(Path file) {
    JsonNode schema = MAPPER.readTree(ContractResources.read(file));
    boolean isEventRoot = schema.has("allOf");
    List<String> lenient = new ArrayList<>();
    collectLenientObjects(schema, "", isEventRoot, lenient);

    assertThat(lenient).as("object schemas without additionalProperties: false").isEmpty();
  }

  private static void collectLenientObjects(
      JsonNode node, String pointer, boolean skipThisNode, List<String> lenient) {
    if (!node.isObject()) {
      return;
    }
    if (!skipThisNode
        && node.has("properties")
        && node.path("additionalProperties").asBoolean(true)) {
      lenient.add(pointer.isEmpty() ? "/" : pointer);
    }
    for (Map.Entry<String, JsonNode> child : node.properties()) {
      collectLenientObjects(child.getValue(), pointer + "/" + child.getKey(), false, lenient);
    }
  }
}
