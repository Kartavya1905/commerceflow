package dev.commerceflow.catalog.web;

import dev.commerceflow.catalog.product.ProductPage;
import dev.commerceflow.catalog.product.ProductQueries;
import dev.commerceflow.catalog.product.ProductView;
import dev.commerceflow.catalog.product.SortOrder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public browsing, plus the internal price lookup used by order-service at checkout. */
@RestController
class ProductController {

  private final ProductQueries queries;

  ProductController(ProductQueries queries) {
    this.queries = queries;
  }

  @GetMapping("/api/v1/products")
  ProductPage list(
      @RequestParam(required = false) @Size(max = 200) String q,
      @RequestParam(required = false) @Size(max = 60) String category,
      @RequestParam(defaultValue = "relevance") SortOrder sort,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return queries.search(q, category, sort, page, size);
  }

  @GetMapping("/api/v1/products/{productId}")
  ProductView get(@PathVariable UUID productId) {
    return queries.getActive(productId);
  }

  /** Not routed by the gateway. Any authenticated caller may read prices. */
  @GetMapping("/internal/v1/prices")
  List<PriceResponse> prices(@RequestParam @NotEmpty @Size(max = 50) List<UUID> ids) {
    return queries.activePrices(ids).stream().map(PriceResponse::of).toList();
  }

  record PriceResponse(UUID productId, String name, long priceMinor, String currency) {
    static PriceResponse of(ProductView p) {
      return new PriceResponse(p.id(), p.name(), p.priceMinor(), p.currency());
    }
  }
}
