package dev.commerceflow.catalog.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** The admin-editable fields, validated with the same limits as the database constraints. */
public record ProductFields(
    @NotBlank @Pattern(regexp = "^[A-Z0-9-]{3,40}$") String sku,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 4000) String description,
    @NotBlank @Size(max = 60) String category,
    @NotNull @PositiveOrZero Long priceMinor,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
