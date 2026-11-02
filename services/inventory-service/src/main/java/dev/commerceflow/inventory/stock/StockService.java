package dev.commerceflow.inventory.stock;

import dev.commerceflow.web.ApiException;
import dev.commerceflow.web.audit.AuditWriter;
import dev.commerceflow.web.audit.AuditWriter.Outcome;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin stock levels. Every change is recorded in stock_adjustments and audit_events. */
@Service
public class StockService {

  public enum Reason {
    RESTOCK,
    WRITE_OFF,
    CORRECTION
  }

  public record Stock(UUID productId, int available, int reserved, int committed, long version) {}

  private final JdbcClient jdbc;
  private final AuditWriter audit;
  private final Clock clock;

  public StockService(JdbcClient jdbc, AuditWriter audit, Clock clock) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Optional<Stock> find(UUID productId) {
    return jdbc.sql(
            "select product_id, available, reserved, committed, version from inventory"
                + " where product_id = :id")
        .param("id", productId)
        .query(
            (rs, n) ->
                new Stock(
                    rs.getObject("product_id", UUID.class),
                    rs.getInt("available"),
                    rs.getInt("reserved"),
                    rs.getInt("committed"),
                    rs.getLong("version")))
        .optional();
  }

  /**
   * Adds or removes available stock. Positive deltas create the inventory row on first restock;
   * negative ones may never take available below zero, and never touch reserved or committed stock,
   * which belongs to orders.
   */
  @Transactional
  public Stock adjust(UUID productId, int delta, Reason reason, String note, UUID actor) {
    if (reason == Reason.RESTOCK && delta < 0 || reason == Reason.WRITE_OFF && delta > 0) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "invalid-adjustment",
          "Invalid adjustment",
          "RESTOCK must add stock and WRITE_OFF must remove it.");
    }
    OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
    if (delta > 0) {
      jdbc.sql(
              """
              insert into inventory (product_id, available, total_received, updated_at)
              values (:p, :d, :d, :now)
              on conflict (product_id) do update
                 set available = inventory.available + :d,
                     total_received = inventory.total_received + :d,
                     version = inventory.version + 1, updated_at = :now
              """)
          .param("p", productId)
          .param("d", delta)
          .param("now", now)
          .update();
    } else {
      int changed =
          jdbc.sql(
                  """
                  update inventory
                     set available = available + :d, total_written_off = total_written_off - :d,
                         version = version + 1, updated_at = :now
                   where product_id = :p and available >= -:d
                  """)
              .param("d", delta)
              .param("p", productId)
              .param("now", now)
              .update();
      if (changed == 0) {
        throw find(productId).isEmpty()
            ? ApiException.notFound("Inventory for this product")
            : ApiException.conflict(
                "insufficient-stock",
                "Only available stock can be written off, and there is not enough of it.");
      }
    }
    jdbc.sql(
            "insert into stock_adjustments (product_id, delta, reason, note, actor_id, at)"
                + " values (:p, :d, :r, :note, :actor, :now)")
        .param("p", productId)
        .param("d", delta)
        .param("r", reason.name())
        .param("note", note)
        .param("actor", actor)
        .param("now", now)
        .update();
    audit.record(
        actor,
        "ADMIN",
        "STOCK_ADJUSTED",
        "inventory",
        productId.toString(),
        Outcome.SUCCESS,
        Map.of("delta", delta, "reason", reason.name()));
    return find(productId).orElseThrow();
  }
}
