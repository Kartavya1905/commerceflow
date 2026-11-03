package dev.commerceflow.gateway;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.time.Clock;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

  /**
   * One bucket per signed-in user, otherwise one per client IP: the TCP peer, or, behind the web
   * app's nginx, the address nginx saw ({@link ClientAddress}). X-Forwarded-For from anyone else is
   * ignored, or any client could pick its own bucket.
   *
   * <p>If Redis is unavailable the limiter lets requests through (fails open). For this system,
   * availability during a Redis outage is worth more than strict limiting; login stays protected by
   * per-account lockout in identity-service.
   */
  @Bean
  KeyResolver clientKeyResolver(GatewayProperties properties) {
    ClientAddress clients = new ClientAddress(properties.trustedProxies(), Clock.systemUTC());
    return exchange ->
        exchange
            .getPrincipal()
            .map(Principal::getName)
            .map(name -> "user:" + name)
            .switchIfEmpty(
                Mono.fromSupplier(
                    () -> {
                      InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
                      return "ip:"
                          + clients.of(
                              remote == null ? null : remote.getAddress(),
                              exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"));
                    }));
  }

  /**
   * The rate limiter answers 429 with X-RateLimit-* headers but no Retry-After; RFC 9110 clients
   * look for Retry-After. The bucket refills continuously, so one second is a fair hint.
   */
  @Bean
  WebFilter retryAfterOn429() {
    class RetryAfterFilter implements WebFilter, Ordered {
      @Override
      public Mono<Void> filter(
          org.springframework.web.server.ServerWebExchange exchange,
          org.springframework.web.server.WebFilterChain chain) {
        exchange
            .getResponse()
            .beforeCommit(
                () -> {
                  if (exchange.getResponse().getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                      && !exchange.getResponse().getHeaders().containsHeader("Retry-After")) {
                    exchange.getResponse().getHeaders().set("Retry-After", "1");
                  }
                  return Mono.empty();
                });
        return chain.filter(exchange);
      }

      @Override
      public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
      }
    }
    return new RetryAfterFilter();
  }
}
