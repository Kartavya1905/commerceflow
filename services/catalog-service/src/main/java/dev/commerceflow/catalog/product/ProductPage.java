package dev.commerceflow.catalog.product;

import java.util.List;

public record ProductPage(List<ProductView> items, int page, int size, long totalItems) {}
