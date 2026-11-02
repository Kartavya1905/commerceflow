package dev.commerceflow.order.order;

import dev.commerceflow.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * One canonical form per logical request: lines merged by product and sorted by id. The idempotency
 * hash is taken over this form, so the same order sent with its items in a different order, or with
 * a product split over two lines, is recognised as the same request.
 */
record CanonicalOrder(List<Line> lines, String paymentToken, byte[] requestHash) {

  record Line(UUID productId, int quantity) {}

  static final int MAX_QUANTITY = 1000;

  static CanonicalOrder of(List<Line> requested, String paymentToken) {
    Map<UUID, Integer> merged = new TreeMap<>();
    for (Line line : requested) {
      merged.merge(line.productId(), line.quantity(), Integer::sum);
    }
    List<Line> lines =
        merged.entrySet().stream().map(e -> new Line(e.getKey(), e.getValue())).toList();
    for (Line line : lines) {
      if (line.quantity() > MAX_QUANTITY) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "quantity-too-large",
            "Quantity too large",
            "At most " + MAX_QUANTITY + " of one product per order.");
      }
    }
    return new CanonicalOrder(lines, paymentToken, hash(lines, paymentToken));
  }

  private static byte[] hash(List<Line> lines, String paymentToken) {
    StringBuilder canonical = new StringBuilder();
    for (Line line : lines) {
      canonical.append(line.productId()).append(':').append(line.quantity()).append(';');
    }
    canonical.append('|').append(paymentToken);
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
