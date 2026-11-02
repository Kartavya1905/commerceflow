package dev.commerceflow.gateway;

import dev.commerceflow.security.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Edge security. The gateway rejects requests that are obviously not allowed (no token, wrong role,
 * unknown path) before they cost a service anything; every service still re-checks.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
    ServerAuthenticationEntryPoint unauthorized =
        (exchange, e) -> {
          exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
          return problem(
              exchange,
              HttpStatus.UNAUTHORIZED,
              "unauthorized",
              "Unauthorized",
              "A valid access token is required.");
        };
    ServerAccessDeniedHandler forbidden =
        (exchange, e) ->
            problem(
                exchange,
                HttpStatus.FORBIDDEN,
                "forbidden",
                "Forbidden",
                "You are not allowed to do this.");

    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .cors(cors -> {})
        .authorizeExchange(
            ex ->
                ex.pathMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .pathMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh")
                    .permitAll()
                    .pathMatchers(
                        HttpMethod.GET,
                        "/.well-known/jwks.json",
                        "/api/v1/products",
                        "/api/v1/products/**")
                    .permitAll()
                    // Served only on the management port (8090), which is not published; on the
                    // public port nothing handles these paths, so they answer 404.
                    .pathMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus")
                    .permitAll()
                    .pathMatchers("/internal/**")
                    .denyAll()
                    .pathMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(
                        jwt ->
                            jwt.jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(Jwts.reactiveRolesConverter()))
                    .authenticationEntryPoint(unauthorized))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
        .headers(
            h ->
                h.contentSecurityPolicy(
                        csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                    .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
        .build();
  }

  @Bean
  ReactiveJwtDecoder jwtDecoder(GatewayProperties properties) {
    return Jwts.reactiveDecoder(properties.jwkSetUri());
  }

  /** Only the frontend's origin may call the API from a browser, with credentials (cookies). */
  @Bean
  CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(properties.allowedOrigins());
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(
        List.of(
            "Authorization",
            "Content-Type",
            "Idempotency-Key",
            "X-Correlation-Id",
            "X-Token-Delivery"));
    cors.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After", "Location"));
    cors.setAllowCredentials(true);
    cors.setMaxAge(Duration.ofHours(1));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    return source;
  }

  /** The same RFC 9457 shape the services use, so clients parse one error format. */
  private static Mono<Void> problem(
      ServerWebExchange exchange, HttpStatus status, String type, String title, String detail) {
    String correlationId =
        String.valueOf(exchange.getAttributes().getOrDefault(CorrelationIdWebFilter.ATTRIBUTE, ""));
    String body =
        "{\"type\":\"/problems/%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"correlationId\":\"%s\"}"
            .formatted(type, title, status.value(), detail, correlationId);
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    DataBuffer buffer =
        exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    return exchange.getResponse().writeWith(Mono.just(buffer));
  }
}
