package dev.commerceflow.systemtests;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Kafka from outside the services: extra deliveries of real records, replays from offset 0, and
 * consumer lag. Copies are byte-for-byte the original (key, value, headers), which is exactly what
 * an at-least-once redelivery looks like to a consumer.
 */
final class KafkaTools {

  static final String BOOTSTRAP = "localhost:9092";

  /** Every topic the checkout reads; the commands and events of all three consumers. */
  static final List<String> CHECKOUT_TOPICS =
      List.of(
          "order.events.v1",
          "inventory.commands.v1",
          "inventory.events.v1",
          "payment.commands.v1",
          "payment.events.v1");

  static final List<String> GROUPS =
      List.of("order-service", "inventory-service", "payment-service");

  private static final String COPY_HEADER = "cf-system-test-copy";

  static KafkaProducer<String, String> producer() {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    return new KafkaProducer<>(props);
  }

  static KafkaConsumer<String, String> consumer() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "system-test-" + UUID.randomUUID());
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    return new KafkaConsumer<>(props);
  }

  /**
   * While running, every new record on the checkout topics is delivered twice more: each command
   * and event reaches its consumer three times in all. Copies are marked so they are not copied.
   */
  static final class Duplicator implements AutoCloseable {
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong copies = new AtomicLong();
    private final Thread worker;

    Duplicator() {
      KafkaConsumer<String, String> consumer = consumer();
      List<TopicPartition> partitions =
          CHECKOUT_TOPICS.stream()
              .flatMap(
                  t ->
                      consumer.partitionsFor(t).stream()
                          .map(p -> new TopicPartition(t, p.partition())))
              .toList();
      consumer.assign(partitions);
      consumer.seekToEnd(partitions);
      partitions.forEach(consumer::position);
      worker =
          Thread.ofPlatform()
              .name("duplicator")
              .start(
                  () -> {
                    try (consumer;
                        KafkaProducer<String, String> producer = producer()) {
                      while (running.get()) {
                        for (ConsumerRecord<String, String> r :
                            consumer.poll(Duration.ofMillis(200))) {
                          if (r.headers().lastHeader(COPY_HEADER) != null) {
                            continue;
                          }
                          for (int i = 0; i < 2; i++) {
                            ProducerRecord<String, String> copy = copyOf(r, r.topic());
                            copy.headers().add(COPY_HEADER, "1".getBytes(StandardCharsets.UTF_8));
                            producer.send(copy);
                            copies.incrementAndGet();
                          }
                        }
                      }
                    }
                  });
    }

    long copies() {
      return copies.get();
    }

    @Override
    public void close() throws InterruptedException {
      running.set(false);
      worker.join(TimeUnit.SECONDS.toMillis(10));
    }
  }

  /** Re-publishes every record still on the checkout topics, from offset 0. */
  /** Where each checkout partition ends now: the start of what a run will publish. */
  static Map<TopicPartition, Long> endOffsets() {
    try (KafkaConsumer<String, String> consumer = consumer()) {
      List<TopicPartition> partitions =
          CHECKOUT_TOPICS.stream()
              .flatMap(
                  t ->
                      consumer.partitionsFor(t).stream()
                          .map(p -> new TopicPartition(t, p.partition())))
              .toList();
      return new HashMap<>(consumer.endOffsets(partitions));
    }
  }

  /**
   * Re-publishes every original record the checkout topics received since {@code from}: each
   * command and event of the run, delivered again. Copies (the duplicator's, and earlier replays',
   * which are marked) are skipped, and these are marked too, so replays never compound.
   *
   * @return how many records were replayed
   */
  static long replay(Map<TopicPartition, Long> from) throws Exception {
    long replayed = 0;
    try (KafkaConsumer<String, String> consumer = consumer();
        KafkaProducer<String, String> producer = producer()) {
      List<TopicPartition> partitions = List.copyOf(from.keySet());
      consumer.assign(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      partitions.forEach(p -> consumer.seek(p, from.get(p)));
      while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
          boolean inRange = r.offset() < end.get(new TopicPartition(r.topic(), r.partition()));
          if (inRange && r.headers().lastHeader(COPY_HEADER) == null) {
            ProducerRecord<String, String> copy = copyOf(r, r.topic());
            copy.headers().add(COPY_HEADER, "replay".getBytes(StandardCharsets.UTF_8));
            producer.send(copy);
            replayed++;
          }
        }
      }
      producer.flush();
    }
    return replayed;
  }

  private static ProducerRecord<String, String> copyOf(
      ConsumerRecord<String, String> r, String to) {
    ProducerRecord<String, String> copy = new ProducerRecord<>(to, r.key(), r.value());
    r.headers().forEach(h -> copy.headers().add(h));
    return copy;
  }

  /** Records the services' consumer groups have yet to process on the checkout topics. */
  static long lag() throws Exception {
    Properties props = new Properties();
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
    try (Admin admin = Admin.create(props)) {
      long lag = 0;
      for (String group : GROUPS) {
        Map<TopicPartition, OffsetAndMetadata> committed =
            admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        committed.keySet().stream()
            .filter(tp -> CHECKOUT_TOPICS.contains(tp.topic()))
            .forEach(tp -> latest.put(tp, OffsetSpec.latest()));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
            admin.listOffsets(latest).all().get();
        for (var entry : ends.entrySet()) {
          OffsetAndMetadata at = committed.get(entry.getKey());
          lag += entry.getValue().offset() - (at == null ? 0 : at.offset());
        }
      }
      return lag;
    }
  }

  /** Records parked in the checkout topics' dead-letter topics so far. */
  static long deadLettered() throws Exception {
    Properties props = new Properties();
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
    try (Admin admin = Admin.create(props)) {
      Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
      for (String topic : CHECKOUT_TOPICS) {
        admin
            .describeTopics(List.of(topic + ".dlt"))
            .allTopicNames()
            .get()
            .get(topic + ".dlt")
            .partitions()
            .forEach(
                p ->
                    latest.put(
                        new TopicPartition(topic + ".dlt", p.partition()), OffsetSpec.latest()));
      }
      return admin.listOffsets(latest).all().get().values().stream()
          .mapToLong(ListOffsetsResult.ListOffsetsResultInfo::offset)
          .sum();
    }
  }
}
