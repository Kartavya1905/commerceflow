package dev.commerceflow.testsupport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads a topic from the beginning with a throwaway consumer group, for asserting on output. */
public final class TopicReader {

  private TopicReader() {}

  /**
   * Records whose value matches, polling until at least {@code atLeast} arrive or time runs out.
   */
  public static List<ConsumerRecord<String, String>> read(
      String bootstrapServers,
      String topic,
      Predicate<ConsumerRecord<String, String>> match,
      int atLeast,
      Duration timeout) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    Instant deadline = Instant.now().plus(timeout);
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      while (found.size() < atLeast && Instant.now().isBefore(deadline)) {
        consumer
            .poll(Duration.ofMillis(300))
            .forEach(
                r -> {
                  if (match.test(r)) {
                    found.add(r);
                  }
                });
      }
    }
    return found;
  }
}
