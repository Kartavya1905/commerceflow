package dev.commerceflow.inventory.messaging;

import dev.commerceflow.inventory.reservation.ReservationDecision;
import dev.commerceflow.inventory.reservation.ReservationLine;
import dev.commerceflow.inventory.reservation.ReservationService;
import dev.commerceflow.inventory.reservation.ReservationService.CommitResult;
import dev.commerceflow.inventory.reservation.ReservationService.ReleaseResult;
import dev.commerceflow.messaging.InboundEvent;
import dev.commerceflow.messaging.InboundEvents;
import dev.commerceflow.messaging.OutboxWriter;
import dev.commerceflow.messaging.PoisonMessageException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Turns order events and saga commands into reservation changes, and publishes each outcome. The
 * dedupe record, the stock change and the outgoing event commit together in one transaction.
 */
@Component
class InventoryListeners {

  private static final Logger log = LoggerFactory.getLogger(InventoryListeners.class);
  private static final String AGGREGATE = "OrderReservation";

  private final InboundEvents inbound;
  private final ReservationService reservations;
  private final OutboxWriter outbox;
  private final MeterRegistry meters;

  InventoryListeners(
      InboundEvents inbound,
      ReservationService reservations,
      OutboxWriter outbox,
      MeterRegistry meters) {
    this.inbound = inbound;
    this.reservations = reservations;
    this.outbox = outbox;
    this.meters = meters;
  }

  /**
   * order.events.v1 also carries OrderConfirmed and OrderFailed; only OrderCreated matters here.
   */
  @KafkaListener(topics = "order.events.v1")
  void onOrderEvent(ConsumerRecord<String, String> record) {
    inbound.handle(
        record,
        "inventory.order-events",
        event -> {
          if (!"OrderCreated".equals(event.eventType())) {
            return;
          }
          requireVersion(event);
          List<ReservationLine> lines = new ArrayList<>();
          for (JsonNode item : event.payload().path("items")) {
            lines.add(
                new ReservationLine(
                    UUID.fromString(item.path("productId").asString()),
                    item.path("quantity").asInt()));
          }
          publish(reservations.reserve(event.uuid("orderId"), lines));
        });
  }

  @KafkaListener(topics = "inventory.commands.v1")
  void onCommand(ConsumerRecord<String, String> record) {
    inbound.handle(
        record,
        "inventory.commands",
        event -> {
          requireVersion(event);
          UUID orderId = event.uuid("orderId");
          switch (event.eventType()) {
            case "CommitInventory" -> {
              CommitResult result = reservations.commit(orderId);
              if (result.committed()) {
                outbox.append("InventoryCommitted", AGGREGATE, orderId, Map.of("orderId", orderId));
              } else {
                outbox.append(
                    "InventoryCommitFailed",
                    AGGREGATE,
                    orderId,
                    Map.of("orderId", orderId, "reason", result.failure().name()));
              }
            }
            case "ReleaseInventory" -> {
              ReleaseResult result = reservations.release(orderId);
              if (result == ReleaseResult.NOT_RELEASABLE) {
                // Committed stock is sold. The saga never asks for this; record it, do not answer.
                meters.counter("inventory.release.refused").increment();
                log.error("ReleaseInventory for committed order {}; nothing released", orderId);
              } else {
                outbox.append("InventoryReleased", AGGREGATE, orderId, Map.of("orderId", orderId));
              }
            }
            default ->
                meters.counter("inventory.commands.unknown", "type", event.eventType()).increment();
          }
        });
  }

  /**
   * Answers with the stored decision. A re-sent OrderCreated (new event id, same order) gets the
   * same answer again, so the saga always hears back. Orders whose reservation has since been
   * committed, released or expired get no answer: the saga has already moved past this step.
   */
  private void publish(ReservationDecision decision) {
    UUID orderId = decision.orderId();
    switch (decision.status()) {
      case RESERVED ->
          outbox.append(
              "InventoryReserved",
              AGGREGATE,
              orderId,
              Map.of(
                  "orderId",
                  orderId,
                  "reservations",
                  decision.lines().stream()
                      .map(l -> Map.of("productId", l.productId(), "quantity", l.quantity()))
                      .toList()));
      case REJECTED ->
          outbox.append(
              "InventoryRejected",
              AGGREGATE,
              orderId,
              Map.of(
                  "orderId",
                  orderId,
                  "reason",
                  decision.rejectReason().name(),
                  "productIds",
                  decision.rejectedProductIds()));
      case COMMITTED, RELEASED, EXPIRED ->
          meters
              .counter("inventory.reserve.superseded", "status", decision.status().name())
              .increment();
    }
  }

  private static void requireVersion(InboundEvent event) {
    if (event.eventVersion() != 1) {
      throw new PoisonMessageException(
          "unsupported " + event.eventType() + " version " + event.eventVersion());
    }
  }
}
