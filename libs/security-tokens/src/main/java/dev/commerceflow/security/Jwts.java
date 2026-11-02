package dev.commerceflow.security;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;

/** The token rules every CommerceFlow service shares. */
public final class Jwts {

  /** Value of the {@code iss} claim. An identifier, not a URL anyone fetches. */
  public static final String ISSUER = "https://identity.commerceflow.local";

  /** Value of the {@code aud} claim. */
  public static final String AUDIENCE = "commerceflow";

  /** Claim holding the user's roles, for example {@code ["CUSTOMER"]}. */
  public static final String ROLES_CLAIM = "roles";

  private Jwts() {}

  /** Maps {@code roles: ["ADMIN"]} to the authority {@code ROLE_ADMIN}. */
  public static JwtAuthenticationConverter rolesConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName(ROLES_CLAIM);
    authorities.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }

  /** Signature, expiry (with the default 60 s clock skew), issuer and audience. */
  public static OAuth2TokenValidator<Jwt> validator() {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(ISSUER),
        new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(AUDIENCE)));
  }

  /** A decoder for a known public key: identity-service itself, and tests. */
  public static NimbusJwtDecoder decoder(RSAPublicKey publicKey) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
    decoder.setJwtValidator(validator());
    return decoder;
  }

  /** A decoder that fetches keys from identity-service's JWKS endpoint (cached by Nimbus). */
  public static NimbusJwtDecoder decoder(String jwkSetUri) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    decoder.setJwtValidator(validator());
    return decoder;
  }

  /** The same rules for the reactive gateway. Needs spring-webflux on the classpath. */
  public static NimbusReactiveJwtDecoder reactiveDecoder(String jwkSetUri) {
    NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
    decoder.setJwtValidator(validator());
    return decoder;
  }

  /** {@link #rolesConverter()} adapted for reactive security. */
  public static ReactiveJwtAuthenticationConverterAdapter reactiveRolesConverter() {
    return new ReactiveJwtAuthenticationConverterAdapter(rolesConverter());
  }
}
