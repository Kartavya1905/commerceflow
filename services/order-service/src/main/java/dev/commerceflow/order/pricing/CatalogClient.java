package dev.commerceflow.order.pricing;

import dev.commerceflow.order.config.OrderConfig;
import dev.commerceflow.order.config.OrderConfig.OrderProperties;
import dev.commerceflow.web.ApiException;
import java.net.http.HttpClient;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Authoritative prices from catalog-service's /internal/v1/prices, never from the client and never
 * from a cache. Every failure (timeout, 5xx, open circuit) becomes 503 PRICE_UNAVAILABLE, and
 * nothing is saved.
 */
@Component
public class CatalogClient {

  private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

  public record Price(UUID productId, String name, long priceMinor, String currency) {}

  private final RestClient client;
  private final CircuitBreaker breaker;

  public CatalogClient(OrderProperties properties, CircuitBreakerFactory<?, ?> breakers) {
    JdkClientHttpRequestFactory requests =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(properties.catalogConnectTimeout()).build());
    requests.setReadTimeout(properties.catalogReadTimeout());
    this.client =
        RestClient.builder().baseUrl(properties.catalogUri()).requestFactory(requests).build();
    this.breaker = breakers.create(OrderConfig.CATALOG_BREAKER);
  }

  /**
   * Prices for the products that exist and are active. Missing ids are simply absent from the map.
   * The caller's own access token is forwarded, so catalog sees who is asking.
   */
  public Map<UUID, Price> prices(Collection<UUID> productIds, String bearerToken) {
    return breaker.run(
        () -> fetch(productIds, bearerToken),
        failure -> {
          log.warn("Price lookup failed: {}", failure.toString());
          throw new ApiException(
              HttpStatus.SERVICE_UNAVAILABLE,
              "price-unavailable",
              "Prices unavailable",
              "Prices could not be confirmed right now, so the order was not placed. Try again"
                  + " shortly.");
        });
  }

  private Map<UUID, Price> fetch(Collection<UUID> productIds, String bearerToken) {
    String ids = productIds.stream().map(UUID::toString).collect(Collectors.joining(","));
    List<Price> prices =
        client
            .get()
            .uri(uri -> uri.path("/internal/v1/prices").queryParam("ids", ids).build())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
            .retrieve()
            .body(new ParameterizedTypeReference<List<Price>>() {});
    return prices == null
        ? Map.of()
        : prices.stream().collect(Collectors.toMap(Price::productId, Function.identity()));
  }
}
