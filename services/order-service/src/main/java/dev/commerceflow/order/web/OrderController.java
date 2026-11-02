package dev.commerceflow.order.web;

import dev.commerceflow.order.cart.CartService;
import dev.commerceflow.order.cart.CartService.Cart;
import dev.commerceflow.order.order.OrderQueries;
import dev.commerceflow.order.order.OrderQueries.Page;
import dev.commerceflow.order.order.OrderService;
import dev.commerceflow.order.order.OrderService.PlaceResult;
import dev.commerceflow.order.order.OrderView;
import dev.commerceflow.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The customer id always comes from the token, never from the request. */
@RestController
class OrderController {

  record SetQuantity(@NotNull @Min(1) @Max(1000) Integer quantity) {}

  record OrderLineRequest(@NotNull UUID productId, @NotNull @Min(1) @Max(1000) Integer quantity) {}

  record CreateOrderRequest(
      @NotEmpty @Size(max = 50) List<@Valid @NotNull OrderLineRequest> items,
      @NotNull @Pattern(regexp = "^tok_[a-z0-9_]{1,64}$") String paymentToken) {}

  private final CartService carts;
  private final OrderService orders;
  private final OrderQueries queries;

  OrderController(CartService carts, OrderService orders, OrderQueries queries) {
    this.carts = carts;
    this.orders = orders;
    this.queries = queries;
  }

  // --- Cart ------------------------------------------------------------------------------------

  @GetMapping("/api/v1/cart")
  Cart cart(@AuthenticationPrincipal Jwt jwt) {
    return carts.get(customer(jwt));
  }

  @PutMapping("/api/v1/cart/items/{productId}")
  Cart setItem(
      @PathVariable UUID productId,
      @Valid @RequestBody SetQuantity body,
      @AuthenticationPrincipal Jwt jwt) {
    return carts.setItem(customer(jwt), productId, body.quantity());
  }

  @DeleteMapping("/api/v1/cart/items/{productId}")
  Cart removeItem(@PathVariable UUID productId, @AuthenticationPrincipal Jwt jwt) {
    return carts.removeItem(customer(jwt), productId);
  }

  @DeleteMapping("/api/v1/cart")
  ResponseEntity<Void> clearCart(@AuthenticationPrincipal Jwt jwt) {
    carts.clear(customer(jwt));
    return ResponseEntity.noContent().build();
  }

  // --- Orders ----------------------------------------------------------------------------------

  /** 202 for a new order (it is PENDING until the saga finishes), 200 for a replay of one. */
  @PostMapping("/api/v1/orders")
  ResponseEntity<OrderView> place(
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "^[A-Za-z0-9-]{16,64}$")
          String idempotencyKey,
      @Valid @RequestBody CreateOrderRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    PlaceResult result =
        orders.place(
            customer(jwt),
            idempotencyKey,
            request.items().stream()
                .map(i -> new OrderService.Line(i.productId(), i.quantity()))
                .toList(),
            request.paymentToken(),
            jwt.getTokenValue());
    return result.created()
        ? ResponseEntity.accepted()
            .location(URI.create("/api/v1/orders/" + result.order().id()))
            .body(result.order())
        : ResponseEntity.ok(result.order());
  }

  @GetMapping("/api/v1/orders")
  Page myOrders(
      @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @AuthenticationPrincipal Jwt jwt) {
    return queries.forCustomer(customer(jwt), page, size);
  }

  /** Someone else's order is a 404, exactly like an order that does not exist. */
  @GetMapping("/api/v1/orders/{orderId}")
  OrderView myOrder(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
    return queries
        .findForCustomer(orderId, customer(jwt))
        .orElseThrow(() -> ApiException.notFound("Order"));
  }

  @GetMapping("/api/v1/admin/orders")
  Page allOrders(
      @RequestParam(required = false)
          @Pattern(regexp = "^(PENDING|PAYMENT_PROCESSING|CONFIRMED|FAILED)$")
          String status,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return queries.all(status, page, size);
  }

  private static UUID customer(Jwt jwt) {
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This token has no customer identity.");
    }
  }
}
