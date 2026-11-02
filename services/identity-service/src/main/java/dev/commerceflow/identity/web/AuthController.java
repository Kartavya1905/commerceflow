package dev.commerceflow.identity.web;

import dev.commerceflow.identity.config.IdentityProperties;
import dev.commerceflow.identity.token.SessionService;
import dev.commerceflow.identity.token.TokenService;
import dev.commerceflow.identity.token.TokenService.IssuedTokens;
import dev.commerceflow.identity.user.AccountService;
import dev.commerceflow.identity.user.Role;
import dev.commerceflow.identity.user.User;
import dev.commerceflow.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

  static final String REFRESH_COOKIE = "cf_refresh";
  static final String COOKIE_PATH = "/api/v1/auth";

  /**
   * Browsers get the refresh token only as an HttpOnly cookie, out of reach of page scripts.
   * Non-browser clients (CLI, k6, tests) opt in to receiving it in the body with this header.
   */
  static final String DELIVERY_HEADER = "X-Token-Delivery";

  record RegisterRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 128) String password,
      @NotBlank @Size(max = 100) String displayName) {}

  record LoginRequest(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

  record RefreshRequest(@Size(max = 512) String refreshToken) {}

  private final AccountService accounts;
  private final SessionService sessions;
  private final IdentityProperties properties;

  AuthController(AccountService accounts, SessionService sessions, IdentityProperties properties) {
    this.accounts = accounts;
    this.sessions = sessions;
    this.properties = properties;
  }

  @PostMapping("/register")
  ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
    User user =
        accounts.register(
            request.email(), request.password(), request.displayName(), Role.CUSTOMER);
    return ResponseEntity.created(URI.create("/api/v1/users/me")).body(UserResponse.of(user));
  }

  @PostMapping("/login")
  ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest request,
      @RequestHeader(name = DELIVERY_HEADER, required = false) String delivery) {
    IssuedTokens tokens =
        accounts
            .login(request.email(), request.password())
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "invalid-credentials",
                        "Unauthorized",
                        "Email or password is incorrect."));
    return withTokens(tokens, delivery);
  }

  @PostMapping("/refresh")
  ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
      @Valid @RequestBody(required = false) RefreshRequest body,
      @RequestHeader(name = DELIVERY_HEADER, required = false) String delivery) {
    String raw = body != null && body.refreshToken() != null ? body.refreshToken() : cookieToken;
    if (raw == null || raw.isBlank()) {
      throw sessionExpired();
    }
    IssuedTokens tokens = sessions.refresh(raw).orElseThrow(AuthController::sessionExpired);
    return withTokens(tokens, delivery);
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
    String sid = jwt.getClaimAsString(TokenService.SESSION_CLAIM);
    if (sid != null) {
      sessions.logout(UUID.fromString(jwt.getSubject()), UUID.fromString(sid));
    }
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
        .build();
  }

  private ResponseEntity<TokenResponse> withTokens(IssuedTokens tokens, String delivery) {
    boolean inBody = "body".equalsIgnoreCase(delivery);
    TokenResponse response =
        new TokenResponse(
            tokens.accessToken(),
            "Bearer",
            tokens.expiresInSeconds(),
            inBody ? tokens.refreshToken() : null);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(
            HttpHeaders.SET_COOKIE,
            refreshCookie(tokens.refreshToken(), properties.refreshTokenTtl()).toString())
        .body(response);
  }

  private ResponseCookie refreshCookie(String value, Duration maxAge) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(properties.refreshCookieSecure())
        .sameSite("Strict")
        .path(COOKIE_PATH)
        .maxAge(maxAge)
        .build();
  }

  private static ApiException sessionExpired() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED,
        "invalid-refresh-token",
        "Unauthorized",
        "Your session has ended. Log in again.");
  }
}
