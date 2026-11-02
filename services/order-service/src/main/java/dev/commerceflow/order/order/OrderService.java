package dev.commerceflow.order.order;

import dev.commerceflow.order.order.OrderWriter.KeyedOrder;
import dev.commerceflow.order.pricing.CatalogClient;
import dev.commerceflow.order.pricing.CatalogClient.Price;
import dev.commerceflow.web.ApiException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Places orders. Deliberately not transactional: the catalog call happens first, with no database
 * connection held, and only the final insert runs in a (short) transaction.
 */
@Service
public class OrderService {

  public record PlaceResult(OrderView order, boolean created) {}

  /** A requested line, before merging and sorting into the canonical form. */
  public record Line(UUID productId, int quantity) {}

  private final CatalogClient catalog;
  private final OrderWriter writer;
  private final OrderQueries queries;

  public OrderService(CatalogClient catalog, OrderWriter writer, OrderQueries queries) {
    this.catalog = catalog;
    this.writer = writer;
    this.queries = queries;
  }

  public PlaceResult place(
      UUID customerId,
      String idempotencyKey,
      List<Line> lines,
      String paymentToken,
      String bearerToken) {
    CanonicalOrder order =
        CanonicalOrder.of(
            lines.stream().map(l -> new CanonicalOrder.Line(l.productId(), l.quantity())).toList(),
            paymentToken);

    // Replays are answered before calling catalog: a retry must work even if prices are down.
    Optional<PlaceResult> replay = replay(customerId, idempotencyKey, order);
    if (replay.isPresent()) {
      return replay.get();
    }

    List<UUID> productIds = order.lines().stream().map(CanonicalOrder.Line::productId).toList();
    Map<UUID, Price> prices = catalog.prices(productIds, bearerToken);
    List<UUID> unavailable = productIds.stream().filter(id -> !prices.containsKey(id)).toList();
    if (!unavailable.isEmpty()) {
      throw unavailable(unavailable);
    }
    List<String> currencies = OrderWriter.currencies(prices);
    if (currencies.size() != 1) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "mixed-currencies",
          "Mixed currencies",
          "All products in one order must be priced in the same currency.");
    }
    long total = total(order, prices);

    Optional<UUID> created =
        writer.insertPending(customerId, idempotencyKey, order, prices, currencies.get(0), total);
    if (created.isPresent()) {
      return new PlaceResult(queries.find(created.get()).orElseThrow(), true);
    }
    // A concurrent request with the same key won the insert; answer as its replay.
    return replay(customerId, idempotencyKey, order).orElseThrow();
  }

  private Optional<PlaceResult> replay(UUID customerId, String key, CanonicalOrder order) {
    Optional<KeyedOrder> existing = writer.findByKey(customerId, key);
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    if (!Arrays.equals(existing.get().requestHash(), order.requestHash())) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "idempotency-key-reused",
          "Idempotency key reused",
          "This Idempotency-Key was already used for a different order. Use a new key for a new"
              + " order.");
    }
    return Optional.of(
        new PlaceResult(queries.find(existing.get().orderId()).orElseThrow(), false));
  }

  /** Exact arithmetic: an overflow is a rejected order, never a wrapped-around total. */
  private static long total(CanonicalOrder order, Map<UUID, Price> prices) {
    try {
      long total = 0;
      for (CanonicalOrder.Line line : order.lines()) {
        total =
            Math.addExact(
                total,
                Math.multiplyExact(prices.get(line.productId()).priceMinor(), line.quantity()));
      }
      return total;
    } catch (ArithmeticException e) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "order-too-large",
          "Order too large",
          "The order total is too large to process.");
    }
  }

  /** 422 that names the products, so the client can fix the cart. */
  private static ApiException unavailable(List<UUID> productIds) {
    return new ApiException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "unavailable-products",
            "Products unavailable",
            "Some products do not exist or are no longer sold.")
        .withProperty("productIds", productIds);
  }
}
