package dev.commerceflow.catalog.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Write model. The generated search_vector column is deliberately not mapped. */
@Entity
@Table(name = "products")
public class Product {

  public enum Status {
    ACTIVE,
    ARCHIVED
  }

  @Id private UUID id;

  @Column(nullable = false)
  private String sku;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String description;

  @Column(nullable = false)
  private String category;

  @Column(name = "price_minor", nullable = false)
  private long priceMinor;

  @Column(nullable = false)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  @Version private long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Product() {}

  static Product create(ProductFields fields, Instant now) {
    Product product = new Product();
    product.id = UUID.randomUUID();
    product.status = Status.ACTIVE;
    product.createdAt = now;
    product.apply(fields, now);
    return product;
  }

  void apply(ProductFields fields, Instant now) {
    sku = fields.sku();
    name = fields.name().strip();
    description = fields.description() == null ? "" : fields.description().strip();
    category = fields.category().strip();
    priceMinor = fields.priceMinor();
    currency = fields.currency();
    updatedAt = now;
  }

  /** Idempotent: archiving an archived product changes nothing. */
  boolean archive(Instant now) {
    if (status == Status.ARCHIVED) {
      return false;
    }
    status = Status.ARCHIVED;
    updatedAt = now;
    return true;
  }

  ProductView toView() {
    return new ProductView(
        id, sku, name, description, category, priceMinor, currency, status.name(), version);
  }

  public UUID id() {
    return id;
  }

  public long version() {
    return version;
  }

  public long priceMinor() {
    return priceMinor;
  }
}
