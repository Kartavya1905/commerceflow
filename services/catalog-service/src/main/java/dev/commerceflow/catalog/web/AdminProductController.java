package dev.commerceflow.catalog.web;

import dev.commerceflow.catalog.product.ProductFields;
import dev.commerceflow.catalog.product.ProductService;
import dev.commerceflow.catalog.product.ProductView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Requires ROLE_ADMIN (enforced in SecurityConfig for /api/v1/admin/**). */
@RestController
@RequestMapping("/api/v1/admin/products")
class AdminProductController {

  /** The product fields plus the version the admin last read, flat as in catalog-service.yaml. */
  record UpdateProductRequest(
      @NotBlank @Pattern(regexp = "^[A-Z0-9-]{3,40}$") String sku,
      @NotBlank @Size(max = 200) String name,
      @Size(max = 4000) String description,
      @NotBlank @Size(max = 60) String category,
      @NotNull @PositiveOrZero Long priceMinor,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull Long version) {

    ProductFields fields() {
      return new ProductFields(sku, name, description, category, priceMinor, currency);
    }
  }

  private final ProductService products;

  AdminProductController(ProductService products) {
    this.products = products;
  }

  @PostMapping
  ResponseEntity<ProductView> create(
      @Valid @RequestBody ProductFields fields, @AuthenticationPrincipal Jwt admin) {
    ProductView created = products.create(fields, actor(admin));
    return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
  }

  @PutMapping("/{productId}")
  ProductView update(
      @PathVariable UUID productId,
      @Valid @RequestBody UpdateProductRequest request,
      @AuthenticationPrincipal Jwt admin) {
    return products.update(productId, request.fields(), request.version(), actor(admin));
  }

  @PostMapping("/{productId}/archive")
  ResponseEntity<Void> archive(@PathVariable UUID productId, @AuthenticationPrincipal Jwt admin) {
    products.archive(productId, actor(admin));
    return ResponseEntity.noContent().build();
  }

  private static UUID actor(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
