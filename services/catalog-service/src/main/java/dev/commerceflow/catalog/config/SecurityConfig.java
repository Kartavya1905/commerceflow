package dev.commerceflow.catalog.config;

import dev.commerceflow.security.Jwts;
import dev.commerceflow.web.security.ProblemSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The gateway already checks tokens and roles; this service checks them again so a request that
 * reaches it directly (misrouted, or from inside the network) gets no further.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
      throws Exception {
    var entryPoint = ProblemSecurityHandlers.authenticationEntryPoint();
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/*")
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

  /** Keys come from identity-service's JWKS endpoint, fetched lazily and cached. */
  @Bean
  JwtDecoder jwtDecoder(CatalogProperties properties) {
    return Jwts.decoder(properties.jwkSetUri());
  }
}
