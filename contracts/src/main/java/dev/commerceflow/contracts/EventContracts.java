package dev.commerceflow.contracts;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates Kafka messages against the CommerceFlow event contracts.
 *
 * <p>A message is valid when it matches the JSON Schema for its {@code eventType} and also passes
 * the {@link SemanticRules} that JSON Schema cannot express. Producers call this in their contract
 * tests so that a service cannot publish a message its consumers were not promised.
 */
public final class EventContracts {

  public static final String SCHEMA_BASE_URI = "https://schemas.commerceflow.local/events/v1/";
  static final String SCHEMA_CLASSPATH_DIR = "events/v1/";
  static final String TOPIC_CATALOG_RESOURCE = "events/topics.json";

  private final JsonMapper mapper;
  private final SchemaRegistry registry;
  private final TopicCatalog catalog;
  private final Map<String, Schema> schemasByType = new ConcurrentHashMap<>();

  private EventContracts(JsonMapper mapper, SchemaRegistry registry, TopicCatalog catalog) {
    this.mapper = mapper;
    this.registry = registry;
    this.catalog = catalog;
  }

  /** Loads the schemas and topic catalogue bundled on the classpath. */
  public static EventContracts load() {
    JsonMapper mapper =
        JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder ->
                builder.schemaIdResolvers(
                    resolvers ->
                        resolvers.mapPrefix(SCHEMA_BASE_URI, "classpath:" + SCHEMA_CLASSPATH_DIR)));
    return new EventContracts(mapper, registry, readCatalog(mapper));
  }

  public TopicCatalog catalog() {
    return catalog;
  }

  /**
   * Returns every contract violation in {@code eventJson}; an empty list means the message is
   * valid. Each violation starts with the JSON pointer of the offending field where one exists.
   */
  public List<String> validate(String eventJson) {
    JsonNode event;
    try {
      event = mapper.readTree(eventJson);
    } catch (JacksonException e) {
      return List.of("message is not valid JSON: " + e.getOriginalMessage());
    }
    if (!event.isObject()) {
      return List.of("message must be a JSON object");
    }

    String eventType = event.path("eventType").asString("");
    Optional<TopicCatalog.Topic> topic = catalog.topicFor(eventType);
    if (topic.isEmpty()) {
      return List.of("/eventType: unknown event type '" + eventType + "'");
    }

    List<String> violations = new ArrayList<>();
    schemaFor(eventType)
        .validate(
            eventJson,
            InputFormat.JSON,
            context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))
        .forEach(error -> violations.add(error.toString()));

    // Semantic rules assume the structure is right, so only run them on schema-valid messages.
    if (violations.isEmpty()) {
      violations.addAll(SemanticRules.check(event, topic.get()));
    }
    return List.copyOf(violations);
  }

  private Schema schemaFor(String eventType) {
    return schemasByType.computeIfAbsent(
        eventType,
        type -> registry.getSchema(SchemaLocation.of(SCHEMA_BASE_URI + type + ".schema.json")));
  }

  private static TopicCatalog readCatalog(JsonMapper mapper) {
    try (InputStream in = resource(TOPIC_CATALOG_RESOURCE)) {
      return mapper.readValue(in, TopicCatalog.class);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + TOPIC_CATALOG_RESOURCE, e);
    }
  }

  private static InputStream resource(String path) {
    InputStream in = EventContracts.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new IllegalStateException("Missing classpath resource " + path);
    }
    return in;
  }
}
