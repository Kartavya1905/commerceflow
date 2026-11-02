package dev.commerceflow.gateway;

import java.net.InetSocketAddress;
import java.security.Principal;
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
   * One bucket per signed-in user, otherwise one per client IP. The IP is the TCP peer address, not
   * X-Forwarded-For: nothing trusted sits in front of the gateway yet, so that header would let any
   * client pick its own bucket.
   *
   * <p>If Redis is unavailable the limiter lets requests through (fails open). For this system,
   * availability during a Redis outage is worth more than strict limiting; login stays protected by
   * per-account lockout in identity-service.
   */
  @Bean
  KeyResolver clientKeyResolver() {
    return exchange ->
        exchange
            .getPrincipal()
            .map(Principal::getName)
            .map(name -> "user:" + name)
            .switchIfEmpty(
                Mono.fromSupplier(
                    () -> {
                      InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
                      return remote == null || remote.getAddress() == null
                          ? "ip:unknown"
                          : "ip:" + remote.getAddress().getHostAddress();
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
