package dev.commerceflow.messaging;

import dev.commerceflow.contracts.EventContracts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;

@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  EventContracts eventContracts() {
    return EventContracts.load();
  }

  @Bean
  OutboxWriter outboxWriter(
      JdbcClient jdbc,
      EventContracts contracts,
      MessagingProperties properties,
      ObjectProvider<Clock> clock) {
    return new OutboxWriter(jdbc, contracts, properties, clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnProperty(
      name = "commerceflow.messaging.publisher-enabled",
      havingValue = "true",
      matchIfMissing = true)
  @SuppressWarnings("unchecked")
  OutboxPublisher outboxPublisher(
      JdbcClient jdbc,
      TransactionTemplate tx,
      KafkaTemplate<?, ?> kafka,
      MessagingProperties properties,
      ObjectProvider<MeterRegistry> meters) {
    return new OutboxPublisher(
        jdbc, tx, (KafkaTemplate<String, String>) kafka, properties, registry(meters));
  }

  @Bean
  InboundEvents inboundEvents(
      JdbcClient jdbc, TransactionTemplate tx, ObjectProvider<MeterRegistry> meters) {
    return new InboundEvents(jdbc, tx, registry(meters));
  }

  /** Services get a registry from Actuator; without one, metrics are kept in memory only. */
  private static MeterRegistry registry(ObjectProvider<MeterRegistry> meters) {
    return meters.getIfAvailable(SimpleMeterRegistry::new);
  }

  /**
   * Blocking retries on the same partition (ADR-0007): exponential backoff with jitter, a bounded
   * number of attempts, then the record goes to {@code <topic>.dlt} on the same partition number.
   * Poison messages skip the retries. Spring Boot attaches this handler to every listener.
   */
  @Bean
  CommonErrorHandler kafkaErrorHandler(
      KafkaOperations<?, ?> kafka, MessagingProperties properties) {
    DeadLetterPublishingRecoverer toDeadLetter =
        new DeadLetterPublishingRecoverer(
            kafka, (record, e) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
    ExponentialBackOff backOff = new ExponentialBackOff(properties.retryInitial().toMillis(), 2.0);
    backOff.setMaxInterval(properties.retryMax().toMillis());
    backOff.setJitter(properties.retryInitial().toMillis() / 2);
    backOff.setMaxAttempts(Math.max(0, properties.retryAttempts() - 1));
    DefaultErrorHandler handler = new DefaultErrorHandler(toDeadLetter, backOff);
    handler.addNotRetryableExceptions(PoisonMessageException.class);
    return handler;
  }
}
