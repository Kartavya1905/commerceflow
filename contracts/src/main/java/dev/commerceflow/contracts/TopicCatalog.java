package dev.commerceflow.contracts;

import java.util.List;
import java.util.Optional;

/** Typed view of {@code events/topics.json}. */
public record TopicCatalog(String description, DeadLetter deadLetter, List<Topic> topics) {

  public record DeadLetter(String suffix, int retentionDays) {}

  public record Topic(
      String name,
      String keyField,
      int partitions,
      int retentionDays,
      String producer,
      List<String> consumers,
      List<String> messages) {}

  /** The topic that carries {@code eventType}, if any. */
  public Optional<Topic> topicFor(String eventType) {
    return topics.stream().filter(t -> t.messages().contains(eventType)).findFirst();
  }

  public List<String> allMessageTypes() {
    return topics.stream().flatMap(t -> t.messages().stream()).toList();
  }
}
