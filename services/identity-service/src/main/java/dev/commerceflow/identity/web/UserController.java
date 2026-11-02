package dev.commerceflow.identity.web;

import com.nimbusds.jose.jwk.JWKSet;
import dev.commerceflow.identity.user.UserRepository;
import dev.commerceflow.web.ApiException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserController {

  private final UserRepository users;
  private final JWKSet publicJwkSet;

  UserController(UserRepository users, JWKSet publicJwkSet) {
    this.users = users;
    this.publicJwkSet = publicJwkSet;
  }

  /** The caller's own profile. The id comes from the token, never from the request. */
  @GetMapping("/api/v1/users/me")
  UserResponse me(@AuthenticationPrincipal Jwt jwt) {
    return users
        .findById(UUID.fromString(jwt.getSubject()))
        .map(UserResponse::of)
        .orElseThrow(() -> ApiException.notFound("User"));
  }

  /** Public keys only (toPublicJWKSet would also strip private parts if any slipped in). */
  @GetMapping("/.well-known/jwks.json")
  ResponseEntity<Map<String, Object>> jwks() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .body(publicJwkSet.toPublicJWKSet().toJSONObject());
  }
}
