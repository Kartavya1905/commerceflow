package dev.commerceflow.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param trustedProxies hosts in front of the gateway whose X-Forwarded-For is believed (the web
 *     app's nginx); empty means the gateway is the edge and believes no one
 */
@ConfigurationProperties("commerceflow.gateway")
public record GatewayProperties(
    String jwkSetUri, List<String> allowedOrigins, List<String> trustedProxies) {}
