package dev.commerceflow.inventory.reservation;

import dev.commerceflow.inventory.config.InventoryConfig.InventoryProperties;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Long-stop safety net. The saga releases reservations; this only catches ones it never resolved.
 * Every expiry is logged as a warning and counted, because each one points at a saga bug.
 */
@Component
class ReservationExpiryJob {

  private final ReservationService reservations;
  private final InventoryProperties properties;

  ReservationExpiryJob(ReservationService reservations, InventoryProperties properties) {
    this.reservations = reservations;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  void expireOverdue() {
    for (UUID orderId : reservations.overdue(properties.expiryBatchSize())) {
      reservations.expireOne(orderId);
    }
  }
}
