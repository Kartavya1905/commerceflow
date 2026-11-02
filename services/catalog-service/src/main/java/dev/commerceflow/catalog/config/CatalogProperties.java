package dev.commerceflow.catalog.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("commerceflow.catalog")
public record CatalogProperties(
    String jwkSetUri, Duration productCacheTtl, Duration pageCacheTtl) {}
