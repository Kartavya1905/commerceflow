package dev.commerceflow.contracts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Cross-field rules that JSON Schema cannot express. Each one protects an invariant from
 * ARCHITECTURE.md section B.3 at the message boundary.
 */
final class SemanticRules {

  private SemanticRules() {}

  static List<String> check(JsonNode event, TopicCatalog.Topic topic) {
    List<String> violations = new ArrayList<>();
    JsonNode payload = event.path("payload");

    // The Kafka key is the aggregate id; the payload must agree, or ordering per key is a lie.
    String aggregateId = event.path("aggregateId").asString("");
    String keyValue = payload.path(topic.keyField()).asString("");
    if (!aggregateId.equals(keyValue)) {
      violations.add(
          "/payload/%s: must equal aggregateId (%s) because it is the message key for %s"
              .formatted(topic.keyField(), aggregateId, topic.name()));
    }

    switch (event.path("eventType").asString("")) {
      case "OrderCreated" -> checkOrderCreated(payload, violations);
      case "InventoryReserved" ->
          checkUniqueProducts(payload.path("reservations"), "/payload/reservations", violations);
      default -> {}
    }
    return violations;
  }

  private static void checkOrderCreated(JsonNode payload, List<String> violations) {
    JsonNode items = payload.path("items");
    checkUniqueProducts(items, "/payload/items", violations);

    long expectedTotal = 0;
    try {
      for (JsonNode item : items) {
        long line =
            Math.multiplyExact(
                item.path("quantity").asLong(), item.path("unitPriceMinor").asLong());
        expectedTotal = Math.addExact(expectedTotal, line);
      }
    } catch (ArithmeticException overflow) {
      violations.add("/payload/totalMinor: line totals overflow a 64-bit integer");
      return;
    }
    long total = payload.path("totalMinor").asLong();
    if (total != expectedTotal) {
      violations.add(
          "/payload/totalMinor: %d does not equal the sum of quantity x unitPriceMinor (%d)"
              .formatted(total, expectedTotal));
    }
  }

  private static void checkUniqueProducts(JsonNode lines, String pointer, List<String> violations) {
    Set<String> seen = new HashSet<>();
    for (JsonNode line : lines) {
      String productId = line.path("productId").asString("");
      if (!seen.add(productId)) {
        violations.add(
            "%s: productId %s appears more than once; merge the lines"
                .formatted(pointer, productId));
      }
    }
  }
}
