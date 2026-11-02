package dev.commerceflow.order.order;

import dev.commerceflow.messaging.OutboxWriter;
import dev.commerceflow.order.pricing.CatalogClient.Price;
import dev.commerceflow.order.saga.SagaOrchestrator;
import dev.commerceflow.web.audit.AuditWriter;
import dev.commerceflow.web.audit.AuditWriter.Outcome;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The short transaction that saves an order. Pricing has already happened, outside it. */
@Component
class OrderWriter {

  /** The id and request hash stored under an idempotency key. */
  record KeyedOrder(UUID orderId, byte[] requestHash) {}

  private final JdbcClient jdbc;
  private final AuditWriter audit;
  private final OutboxWriter outbox;
  private final SagaOrchestrator saga;
  private final Clock clock;

  OrderWriter(
      JdbcClient jdbc, AuditWriter audit, OutboxWriter outbox, SagaOrchestrator saga, Clock clock) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.outbox = outbox;
    this.saga = saga;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Optional<KeyedOrder> findByKey(UUID customerId, String idempotencyKey) {
    return jdbc.sql(
            "select id, request_hash from orders where customer_id = :c and idempotency_key = :k")
        .param("c", customerId)
        .param("k", idempotencyKey)
        .query(
            (rs, n) -> new KeyedOrder(rs.getObject("id", UUID.class), rs.getBytes("request_hash")))
        .optional();
  }

  /**
   * Inserts the order as PENDING, or returns empty if another request with the same customer and
   * key got there first. "on conflict do nothing" waits for that other transaction, so the caller
   * can then read its order instead of failing.
   */
  @Transactional
  public Optional<UUID> insertPending(
      UUID customerId,
      String idempotencyKey,
      CanonicalOrder order,
      Map<UUID, Price> prices,
      String currency,
      long totalMinor) {
    UUID orderId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
    int inserted =
        jdbc.sql(
                """
                insert into orders (id, customer_id, status, total_minor, currency, payment_token,
                                    idempotency_key, request_hash, created_at, updated_at)
                values (:id, :customer, 'PENDING', :total, :currency, :token, :key, :hash, :now, :now)
                on conflict (customer_id, idempotency_key) do nothing
                """)
            .param("id", orderId)
            .param("customer", customerId)
            .param("total", totalMinor)
            .param("currency", currency)
            .param("token", order.paymentToken())
            .param("key", idempotencyKey)
            .param("hash", order.requestHash())
            .param("now", now)
            .update();
    if (inserted == 0) {
      return Optional.empty();
    }
    for (CanonicalOrder.Line line : order.lines()) {
      Price price = prices.get(line.productId());
      jdbc.sql(
              """
              insert into order_items (order_id, product_id, product_name, unit_price_minor, quantity)
              values (:order, :product, :name, :price, :qty)
              """)
          .param("order", orderId)
          .param("product", line.productId())
          .param("name", price.name())
          .param("price", price.priceMinor())
          .param("qty", line.quantity())
          .update();
    }
    jdbc.sql(
            "insert into order_status_history (order_id, from_status, to_status, at)"
                + " values (:order, null, 'PENDING', :now)")
        .param("order", orderId)
        .param("now", now)
        .update();
    audit.record(
        customerId,
        "CUSTOMER",
        "ORDER_PLACED",
        "order",
        orderId.toString(),
        Outcome.SUCCESS,
        Map.of("totalMinor", totalMinor, "currency", currency, "lines", order.lines().size()));
    // Same transaction as the order: the saga and OrderCreated exist if and only if it does.
    saga.start(orderId);
    outbox.append(
        "OrderCreated",
        "Order",
        orderId,
        Map.of(
            "orderId", orderId,
            "customerId", customerId,
            "items",
                order.lines().stream()
                    .map(
                        line ->
                            Map.of(
                                "productId", line.productId(),
                                "quantity", line.quantity(),
                                "unitPriceMinor", prices.get(line.productId()).priceMinor()))
                    .toList(),
            "totalMinor", totalMinor,
            "currency", currency));
    return Optional.of(orderId);
  }

  static List<String> currencies(Map<UUID, Price> prices) {
    return prices.values().stream().map(Price::currency).distinct().toList();
  }
}
