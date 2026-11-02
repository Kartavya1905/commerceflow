package dev.commerceflow.identity.config;

import dev.commerceflow.security.Jwts;
import dev.commerceflow.web.security.ProblemSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  /**
   * Stateless bearer-token API. CSRF protection is off because no endpoint relies on ambient
   * cookies for authorisation, except refresh and logout, whose cookie is SameSite=Strict and
   * scoped to /api/v1/auth.
   */
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
      throws Exception {
    var entryPoint = ProblemSecurityHandlers.authenticationEntryPoint();
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json")
                    .permitAll()
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

  /** Argon2id with Spring Security's current recommended parameters. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }
}
