package dev.commerceflow.identity.web;

import dev.commerceflow.identity.user.User;
import java.util.UUID;

/** What the API reveals about a user. Never the password hash or lockout state. */
record UserResponse(UUID id, String email, String displayName, String role) {

  static UserResponse of(User user) {
    return new UserResponse(user.id(), user.email(), user.displayName(), user.role().name());
  }
}
