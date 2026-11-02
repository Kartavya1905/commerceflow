package dev.commerceflow.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("commerceflow.gateway")
public record GatewayProperties(String jwkSetUri, List<String> allowedOrigins) {}
