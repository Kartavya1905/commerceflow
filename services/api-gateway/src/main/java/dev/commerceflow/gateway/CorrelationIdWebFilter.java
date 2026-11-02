package dev.commerceflow.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Assigns the correlation id at the edge and forwards it to the service, so one id ties together
 * the gateway's response, every service's logs, and (later) Kafka messages. Runs before Spring
 * Security, so even a 401 carries it.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

  public static final String HEADER = "X-Correlation-Id";
  public static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".id";
  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String candidate = exchange.getRequest().getHeaders().getFirst(HEADER);
    String id =
        candidate != null && SAFE.matcher(candidate).matches()
            ? candidate
            : UUID.randomUUID().toString();

    ServerHttpRequest request =
        exchange.getRequest().mutate().headers(h -> h.set(HEADER, id)).build();
    exchange.getResponse().getHeaders().set(HEADER, id);
    exchange.getAttributes().put(ATTRIBUTE, id);
    return chain.filter(exchange.mutate().request(request).build());
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
