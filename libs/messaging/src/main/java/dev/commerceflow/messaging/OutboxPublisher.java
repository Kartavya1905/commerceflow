package dev.commerceflow.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends outbox rows to Kafka, oldest first, and marks them published once Kafka has acknowledged
 * them (acks=all, idempotent producer).
 *
 * <ul>
 *   <li><b>At-least-once.</b> A crash after the acknowledgement and before the mark sends the row
 *       again; consumers recognise the repeated event id.
 *   <li><b>One publisher per service.</b> Each round takes a transaction-scoped advisory lock;
 *       other instances skip the round. Parallel publishers would interleave rows and break the
 *       per-order ordering consumers rely on.
 *   <li><b>Kafka outages</b> leave rows unpublished; the loop backs off exponentially and the
 *       backlog drains when Kafka returns. Nothing is lost, only delayed.
 * </ul>
 */
public class OutboxPublisher implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  private static final Duration CLEANUP_EVERY = Duration.ofHours(1);
  private static final long SEND_TIMEOUT_SECONDS = 35;

  private record Row(
      UUID id,
      String topic,
      String key,
      String envelope,
      String eventType,
      Map<String, String> headers) {}

  /** Outcome of one round; failed means Kafka refused at least one row. */
  record Round(int sent, boolean failed) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final KafkaTemplate<String, String> kafka;
  private final MessagingProperties properties;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Counter published;
  private final Counter failures;
  private final AtomicLong pending = new AtomicLong();
  private final AtomicLong oldestAgeSeconds = new AtomicLong();

  private volatile boolean running;
  private Thread worker;
  private Thread monitor;
  private Instant lastCleanup = Instant.EPOCH;

  public OutboxPublisher(
      JdbcClient jdbc,
      TransactionTemplate tx,
      KafkaTemplate<String, String> kafka,
      MessagingProperties properties,
      MeterRegistry meters) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.kafka = kafka;
    this.properties = properties;
    this.published = meters.counter("outbox.published");
    this.failures = meters.counter("outbox.publish.failures");
    Gauge.builder("outbox.pending", pending, AtomicLong::get)
        .description("Outbox rows not yet acknowledged by Kafka")
        .register(meters);
    Gauge.builder("outbox.oldest.unpublished.age", oldestAgeSeconds, AtomicLong::get)
        .description("Age of the oldest unpublished outbox row")
        .baseUnit("seconds")
        .register(meters);
  }

  /** One round: publish up to batchSize rows. Public for tests, which drive rounds directly. */
  public Round publishRound() {
    Round round =
        tx.execute(
            status -> {
              boolean leader =
                  Boolean.TRUE.equals(
                      jdbc.sql("select pg_try_advisory_xact_lock(hashtext(:key))")
                          .param("key", "outbox-publisher:" + properties.producer())
                          .query(Boolean.class)
                          .single());
              if (!leader) {
                return new Round(0, false);
              }
              List<Row> rows =
                  jdbc.sql(
                          """
                          select id, topic, partition_key, envelope, event_type, headers::text as h
                            from outbox_events
                           where published_at is null
                           order by seq
                           limit :n
                             for update
                          """)
                      .param("n", properties.batchSize())
                      .query(
                          (rs, i) ->
                              new Row(
                                  rs.getObject("id", UUID.class),
                                  rs.getString("topic"),
                                  rs.getString("partition_key"),
                                  rs.getString("envelope"),
                                  rs.getString("event_type"),
                                  json.readValue(rs.getString("h"), new TypeReference<>() {})))
                      .list();
              return send(rows);
            });
    return round;
  }

  /**
   * Sends every row, then waits for each acknowledgement in order. With an idempotent producer a
   * failed record also fails the records queued after it on the same partition, so a later event
   * for an order can never be acknowledged ahead of an earlier one that failed.
   */
  private Round send(List<Row> rows) {
    List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>();
    for (Row row : rows) {
      ProducerRecord<String, String> record =
          new ProducerRecord<>(row.topic(), row.key(), row.envelope());
      record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
      record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
      row.headers().forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
      futures.add(kafka.send(record));
    }

    List<UUID> acknowledged = new ArrayList<>();
    String firstError = null;
    for (int i = 0; i < rows.size(); i++) {
      try {
        futures.get(i).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        acknowledged.add(rows.get(i).id());
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        firstError = firstError == null ? e.toString() : firstError;
        jdbc.sql(
                "update outbox_events set publish_attempts = publish_attempts + 1,"
                    + " last_error = :error where id = :id")
            .param("error", truncate(e.toString()))
            .param("id", rows.get(i).id())
            .update();
      }
    }
    if (!acknowledged.isEmpty()) {
      jdbc.sql(
              "update outbox_events set published_at = now(),"
                  + " publish_attempts = publish_attempts + 1 where id in (:ids)")
          .param("ids", acknowledged)
          .update();
      published.increment(acknowledged.size());
    }
    if (firstError != null) {
      failures.increment(rows.size() - acknowledged.size());
      log.warn(
          "Kafka did not acknowledge {} of {} outbox rows: {}",
          rows.size() - acknowledged.size(),
          rows.size(),
          firstError);
    }
    return new Round(acknowledged.size(), firstError != null);
  }

  /**
   * Runs on its own thread, not after each publish round: during a Kafka outage the publisher is
   * blocked waiting for acknowledgements, and a gauge refreshed only by it would freeze at its last
   * value exactly when the backlog is growing.
   */
  void refreshBacklogMetrics() {
    jdbc.sql(
            "select count(*) as n, min(created_at) as oldest from outbox_events"
                + " where published_at is null")
        .query(
            rs -> {
              pending.set(rs.getLong("n"));
              OffsetDateTime oldest = rs.getObject("oldest", OffsetDateTime.class);
              oldestAgeSeconds.set(
                  oldest == null
                      ? 0
                      : Duration.between(oldest.toInstant(), Instant.now()).toSeconds());
            });
  }

  /** Deletes published outbox rows and processed-event records past their retention. */
  public void cleanUp() {
    int outbox =
        jdbc.sql("delete from outbox_events where published_at < now() - cast(:age as interval)")
            .param("age", properties.publishedRetention().toSeconds() + " seconds")
            .update();
    int inbox =
        jdbc.sql("delete from processed_events where processed_at < now() - cast(:age as interval)")
            .param("age", properties.processedRetention().toSeconds() + " seconds")
            .update();
    if (outbox + inbox > 0) {
      log.info("Removed {} published outbox rows and {} processed-event records", outbox, inbox);
    }
  }

  private void loop() {
    int consecutiveFailures = 0;
    while (running) {
      Duration pause = properties.pollInterval();
      try {
        Round round = publishRound();
        if (round.failed()) {
          consecutiveFailures++;
          pause = backoff(consecutiveFailures);
        } else {
          consecutiveFailures = 0;
          if (round.sent() == properties.batchSize()) {
            pause = Duration.ZERO; // more waiting: keep draining
          }
        }
        if (Duration.between(lastCleanup, Instant.now()).compareTo(CLEANUP_EVERY) > 0) {
          cleanUp();
          lastCleanup = Instant.now();
        }
      } catch (RuntimeException e) {
        // Database unavailable, most likely. Back off and try again; never let the loop die.
        consecutiveFailures++;
        pause = backoff(consecutiveFailures);
        log.warn("Outbox round failed: {}", e.toString());
      }
      sleep(pause);
    }
  }

  private void monitorLoop() {
    while (running) {
      try {
        refreshBacklogMetrics();
      } catch (RuntimeException e) {
        log.debug("Could not refresh outbox backlog metrics: {}", e.toString());
      }
      sleep(properties.backlogRefresh());
    }
  }

  private Duration backoff(int failures) {
    long millis = properties.pollInterval().toMillis() * (1L << Math.min(failures, 10));
    return Duration.ofMillis(Math.min(millis, properties.maxBackoff().toMillis()));
  }

  private static void sleep(Duration pause) {
    if (pause.isZero()) {
      return;
    }
    try {
      Thread.sleep(pause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String truncate(String text) {
    return text.length() <= 1000 ? text : text.substring(0, 1000);
  }

  @Override
  public void start() {
    running = true;
    worker = Thread.ofVirtual().name("outbox-publisher").start(this::loop);
    monitor = Thread.ofVirtual().name("outbox-backlog-monitor").start(this::monitorLoop);
  }

  /** Lets the round in progress finish, so no acknowledged row is left unmarked by a shutdown. */
  @Override
  public void stop() {
    running = false;
    if (worker != null) {
      try {
        worker.join(Duration.ofSeconds(SEND_TIMEOUT_SECONDS + 5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
