package dev.commerceflow.order.config;

import dev.commerceflow.security.Jwts;
import dev.commerceflow.web.security.ProblemSecurityHandlers;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.HttpClientErrorException;

@Configuration(proxyBeanMethods = false)
public class OrderConfig {

  public static final String CATALOG_BREAKER = "catalog";

  @ConfigurationProperties("commerceflow.order")
  public record OrderProperties(
      String jwkSetUri,
      String catalogUri,
      Duration catalogConnectTimeout,
      Duration catalogReadTimeout,
      Duration cartTtl,
      int cartMaxItems,
      int breakerWindow,
      int breakerMinimumCalls,
      float breakerFailureRate,
      Duration breakerOpenFor) {}

  /** Everything here needs a signed-in user; admin views need the ADMIN role as well. */
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
      throws Exception {
    var entryPoint = ProblemSecurityHandlers.authenticationEntryPoint();
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(
                        jwt ->
                            jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(Jwts.rolesConverter()))
                    .authenticationEntryPoint(entryPoint))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(ProblemSecurityHandlers.accessDeniedHandler()));
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(OrderProperties properties) {
    return Jwts.decoder(properties.jwkSetUri());
  }

  /**
   * The circuit breaker counts timeouts and server errors, not 4xx: a 4xx means this request was
   * wrong, not that catalog is down, and must not open the circuit for every other customer. The
   * time limiter sits above the HTTP timeouts so they decide first.
   */
  @Bean
  Customizer<Resilience4JCircuitBreakerFactory> catalogBreaker(OrderProperties properties) {
    Duration httpBudget = properties.catalogConnectTimeout().plus(properties.catalogReadTimeout());
    return factory ->
        factory.configure(
            builder ->
                builder
                    .circuitBreakerConfig(
                        CircuitBreakerConfig.custom()
                            .slidingWindowSize(properties.breakerWindow())
                            .minimumNumberOfCalls(properties.breakerMinimumCalls())
                            .failureRateThreshold(properties.breakerFailureRate())
                            .waitDurationInOpenState(properties.breakerOpenFor())
                            .permittedNumberOfCallsInHalfOpenState(1)
                            .ignoreExceptions(HttpClientErrorException.class)
                            .build())
                    .timeLimiterConfig(
                        TimeLimiterConfig.custom()
                            .timeoutDuration(httpBudget.plusMillis(500))
                            .build()),
            CATALOG_BREAKER);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
