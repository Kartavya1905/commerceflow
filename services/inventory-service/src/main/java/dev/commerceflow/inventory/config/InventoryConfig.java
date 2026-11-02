package dev.commerceflow.inventory.config;

import dev.commerceflow.security.Jwts;
import dev.commerceflow.web.security.ProblemSecurityHandlers;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class InventoryConfig {

  @ConfigurationProperties("commerceflow.inventory")
  public record InventoryProperties(
      String jwkSetUri, Duration reservationLongStop, int expiryBatchSize) {}

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
      throws Exception {
    var entryPoint = ProblemSecurityHandlers.authenticationEntryPoint();
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/v1/inventory/*")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
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
  JwtDecoder jwtDecoder(InventoryProperties properties) {
    return Jwts.decoder(properties.jwkSetUri());
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
