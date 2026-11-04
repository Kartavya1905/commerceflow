package dev.commerceflow.messaging;

import dev.commerceflow.contracts.EventContracts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.BackOff;

@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(MessagingAutoConfiguration.class);

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
      ObjectProvider<Clock> clock,
      ObjectProvider<TraceHeaders> trace) {
    return new OutboxWriter(
        jdbc,
        contracts,
        properties,
        clock.getIfAvailable(Clock::systemUTC),
        trace.getIfAvailable(() -> TraceHeaders.NONE));
  }

  /** Only when Micrometer Tracing is on the classpath; without it, events carry no trace. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
  static class TracingConfiguration {

    @Bean
    @ConditionalOnMissingBean(TraceHeaders.class)
    TraceHeaders traceHeaders(
        ObjectProvider<io.micrometer.tracing.Tracer> tracer,
        ObjectProvider<io.micrometer.tracing.propagation.Propagator> propagator) {
      var t = tracer.getIfAvailable();
      var p = propagator.getIfAvailable();
      return t == null || p == null ? TraceHeaders.NONE : new MicrometerTraceHeaders(t, p);
    }
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
      JdbcClient jdbc,
      TransactionTemplate tx,
      MessagingProperties properties,
      EventContracts contracts,
      ObjectProvider<MeterRegistry> meters) {
    if (properties.chaos().active()) {
      log.warn("Fault injection is ON for every consumer: {}", properties.chaos());
    }
    return new InboundEvents(
        jdbc,
        tx,
        registry(meters),
        properties.chaos(),
        type -> contracts.catalog().topicFor(type).isPresent());
  }

  @Bean
  @SuppressWarnings("unchecked")
  DeadLetters deadLetters(ConsumerFactory<?, ?> consumers, KafkaTemplate<?, ?> kafka) {
    return new DeadLetters(
        (ConsumerFactory<String, String>) consumers, (KafkaTemplate<String, String>) kafka);
  }

  /** Services get a registry from Actuator; without one, metrics are kept in memory only. */
  private static MeterRegistry registry(ObjectProvider<MeterRegistry> meters) {
    return meters.getIfAvailable(SimpleMeterRegistry::new);
  }

  /**
   * Blocking retries on the same partition (ADR-0007): exponential backoff with full jitter, a
   * bounded number of attempts, then the record goes to {@code <topic>.dlt} on the same partition
   * number. Poison messages skip the retries. An unreachable database is not the message's fault:
   * those retries go on until it is back (the partition waits, in order), so an outage parks
   * nothing in the dead-letter topic. Spring Boot attaches this handler to every listener.
   */
  @Bean
  CommonErrorHandler kafkaErrorHandler(
      KafkaOperations<?, ?> kafka, MessagingProperties properties) {
    DeadLetterPublishingRecoverer toDeadLetter =
        new DeadLetterPublishingRecoverer(
            kafka, (record, e) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            toDeadLetter,
            Backoff.retries(
                properties.retryInitial(),
                properties.retryMax(),
                Math.max(0, properties.retryAttempts() - 1)));
    BackOff untilBack = Backoff.retries(properties.retryInitial(), properties.outageRetryMax(), -1);
    handler.setBackOffFunction((record, e) -> Outages.isOutage(e) ? untilBack : null);
    handler.addNotRetryableExceptions(PoisonMessageException.class);
    return handler;
  }
}
