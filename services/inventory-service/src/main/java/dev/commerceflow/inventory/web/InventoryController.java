package dev.commerceflow.inventory.web;

import dev.commerceflow.inventory.stock.StockService;
import dev.commerceflow.inventory.stock.StockService.Reason;
import dev.commerceflow.inventory.stock.StockService.Stock;
import dev.commerceflow.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InventoryController {

  record Availability(UUID productId, int available, boolean inStock) {}

  record AdjustmentRequest(
      @NotNull @Min(-100_000) @Max(100_000) Integer delta,
      @NotNull Reason reason,
      @Size(max = 500) String note) {}

  private final StockService stock;

  InventoryController(StockService stock) {
    this.stock = stock;
  }

  /** Public: how many can be bought right now. Reserved and committed stock is not shown. */
  @GetMapping("/api/v1/inventory/{productId}")
  Availability availability(@PathVariable UUID productId) {
    Stock s = stock.find(productId).orElseThrow(() -> ApiException.notFound("Product"));
    return new Availability(s.productId(), s.available(), s.available() > 0);
  }

  @GetMapping("/api/v1/admin/inventory/{productId}")
  Stock get(@PathVariable UUID productId) {
    return stock.find(productId).orElseThrow(() -> ApiException.notFound("Inventory"));
  }

  @PostMapping("/api/v1/admin/inventory/{productId}/adjustments")
  Stock adjust(
      @PathVariable UUID productId,
      @Valid @RequestBody AdjustmentRequest request,
      @AuthenticationPrincipal Jwt admin) {
    if (request.delta() == 0) {
      throw new ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "invalid-adjustment",
          "Invalid adjustment",
          "delta must not be zero.");
    }
    return stock.adjust(
        productId,
        request.delta(),
        request.reason(),
        request.note(),
        UUID.fromString(admin.getSubject()));
  }
}
