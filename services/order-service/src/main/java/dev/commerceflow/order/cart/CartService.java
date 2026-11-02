package dev.commerceflow.order.cart;

import dev.commerceflow.order.config.OrderConfig.OrderProperties;
import dev.commerceflow.web.ApiException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Carts in Redis: one hash per customer (product id → quantity), expiring after a week without
 * changes. Unlike the catalog cache, a cart is data, so a Redis outage is a clear 503 rather than a
 * silent fallback.
 */
@Service
public class CartService {

  public record CartItem(UUID productId, int quantity) {}

  public record Cart(List<CartItem> items) {}

  private final StringRedisTemplate redis;
  private final OrderProperties properties;

  public CartService(StringRedisTemplate redis, OrderProperties properties) {
    this.redis = redis;
    this.properties = properties;
  }

  public Cart get(UUID customerId) {
    return redisCall(() -> read(customerId));
  }

  public Cart setItem(UUID customerId, UUID productId, int quantity) {
    return redisCall(
        () -> {
          String key = key(customerId);
          String field = productId.toString();
          boolean isNew = !redis.opsForHash().hasKey(key, field);
          if (isNew && redis.opsForHash().size(key) >= properties.cartMaxItems()) {
            throw ApiException.conflict(
                "cart-full",
                "A cart holds at most " + properties.cartMaxItems() + " different products.");
          }
          redis.opsForHash().put(key, field, Integer.toString(quantity));
          redis.expire(key, properties.cartTtl());
          return read(customerId);
        });
  }

  public Cart removeItem(UUID customerId, UUID productId) {
    return redisCall(
        () -> {
          redis.opsForHash().delete(key(customerId), productId.toString());
          return read(customerId);
        });
  }

  public void clear(UUID customerId) {
    redisCall(() -> redis.delete(key(customerId)));
  }

  private Cart read(UUID customerId) {
    Map<Object, Object> entries = redis.opsForHash().entries(key(customerId));
    List<CartItem> items =
        entries.entrySet().stream()
            .map(
                e ->
                    new CartItem(
                        UUID.fromString((String) e.getKey()),
                        Integer.parseInt((String) e.getValue())))
            .sorted(Comparator.comparing(CartItem::productId))
            .toList();
    return new Cart(items);
  }

  /** Namespaced so carts cannot collide with other services' keys in the shared Redis. */
  static String key(UUID customerId) {
    return "order:cart:" + customerId;
  }

  private static <T> T redisCall(Supplier<T> call) {
    try {
      return call.get();
    } catch (DataAccessException e) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "cart-unavailable",
          "Cart unavailable",
          "The cart cannot be reached right now. Try again shortly.");
    }
  }
}
