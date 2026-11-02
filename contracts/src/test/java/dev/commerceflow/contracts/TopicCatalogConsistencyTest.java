package dev.commerceflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** topics.json, the schema files and the examples describe the same set of messages. */
class TopicCatalogConsistencyTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final TopicCatalog CATALOG = EventContracts.load().catalog();

  static List<TopicCatalog.Topic> topics() {
    return CATALOG.topics();
  }

  static List<Path> eventSchemaFiles() {
    return ContractResources.eventSchemaFiles();
  }

  @Test
  void everySchemaIsCarriedByExactlyOneTopicAndViceVersa() {
    List<String> schemaTypes =
        ContractResources.eventSchemaFiles().stream().map(ContractResources::typeOf).toList();

    assertThat(CATALOG.allMessageTypes()).doesNotHaveDuplicates();
    assertThat(CATALOG.allMessageTypes()).containsExactlyInAnyOrderElementsOf(schemaTypes);
  }

  @Test
  void everyMessageTypeHasAtLeastOneValidExample() {
    List<String> exampleTypes =
        ContractResources.validExamples().stream().map(ContractResources::typeOf).toList();

    assertThat(exampleTypes).containsAll(CATALOG.allMessageTypes());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("eventSchemaFiles")
  void schemaProducerMatchesTheTopicProducer(Path file) {
    String type = ContractResources.typeOf(file);
    JsonNode schema = MAPPER.readTree(ContractResources.read(file));
    TopicCatalog.Topic topic = CATALOG.topicFor(type).orElseThrow();

    assertThat(schema.path("properties").path("producer").path("const").asString())
        .isEqualTo(topic.producer());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("topics")
  void topicDefinitionIsWellFormed(TopicCatalog.Topic topic) {
    assertThat(topic.name()).matches("^[a-z]+\\.(events|commands)\\.v[0-9]+$");
    assertThat(topic.keyField()).isIn("orderId", "productId", "userId");
    assertThat(topic.partitions()).isBetween(1, 12);
    assertThat(topic.retentionDays()).isPositive();
    assertThat(topic.consumers()).isNotEmpty().doesNotContain(topic.producer());
    assertThat(topic.messages()).isNotEmpty();
  }

  @Test
  void deadLetterTopicsOutliveTheTopicsTheyProtect() {
    int longestRetention =
        CATALOG.topics().stream().mapToInt(TopicCatalog.Topic::retentionDays).max().orElseThrow();

    assertThat(CATALOG.deadLetter().suffix()).isEqualTo(".dlt");
    assertThat(CATALOG.deadLetter().retentionDays()).isGreaterThan(longestRetention);
  }
}
