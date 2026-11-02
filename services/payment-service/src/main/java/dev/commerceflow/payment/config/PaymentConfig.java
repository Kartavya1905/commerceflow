package dev.commerceflow.payment.config;

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

@Configuration(proxyBeanMethods = false)
public class PaymentConfig {

  public static final String PROVIDER_BREAKER = "payment-provider";

  @ConfigurationProperties("commerceflow.payment")
  public record PaymentProperties(
      String jwkSetUri,
      String providerUri,
      String providerApiKey,
      Duration providerConnectTimeout,
      Duration providerReadTimeout,
      Duration reconcileAfter,
      int breakerWindow,
      int breakerMinimumCalls,
      float breakerFailureRate,
      Duration breakerOpenFor) {}

  /** Payments are driven by Kafka commands; the only HTTP API is the admin view. */
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
                    .denyAll())
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
  JwtDecoder jwtDecoder(PaymentProperties properties) {
    return Jwts.decoder(properties.jwkSetUri());
  }

  /**
   * Opens on timeouts and server errors. A decline is the provider working correctly, so the client
   * never throws for one and it never counts. The time limiter sits above the HTTP timeouts so they
   * decide first.
   */
  @Bean
  Customizer<Resilience4JCircuitBreakerFactory> providerBreaker(PaymentProperties properties) {
    Duration httpBudget =
        properties.providerConnectTimeout().plus(properties.providerReadTimeout());
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
                            .build())
                    .timeLimiterConfig(
                        TimeLimiterConfig.custom()
                            .timeoutDuration(httpBudget.plusMillis(500))
                            .build()),
            PROVIDER_BREAKER);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
