package dev.commerceflow.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param producer this service's name, stamped into every envelope (for example order-service)
 * @param pollInterval how often the publisher looks for new outbox rows when idle
 * @param batchSize outbox rows sent per round
 * @param maxBackoff longest pause between publish attempts while Kafka is unavailable
 * @param backlogRefresh how often the outbox backlog gauges are recomputed
 * @param publishedRetention how long published outbox rows are kept
 * @param processedRetention how long consumed event ids are kept; must exceed topic retention
 * @param retryAttempts consumer attempts per record before it goes to the dead-letter topic
 * @param retryInitial first consumer retry delay
 * @param retryMax longest consumer retry delay
 */
@ConfigurationProperties("commerceflow.messaging")
public record MessagingProperties(
    String producer,
    @DefaultValue("200ms") Duration pollInterval,
    @DefaultValue("100") int batchSize,
    @DefaultValue("5s") Duration maxBackoff,
    @DefaultValue("5s") Duration backlogRefresh,
    @DefaultValue("7d") Duration publishedRetention,
    @DefaultValue("14d") Duration processedRetention,
    @DefaultValue("6") int retryAttempts,
    @DefaultValue("200ms") Duration retryInitial,
    @DefaultValue("10s") Duration retryMax) {}
