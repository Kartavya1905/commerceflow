package dev.commerceflow.identity.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One refresh token, stored only as a SHA-256 hash. See V1__identity_schema.sql. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false, updatable = false)
  private byte[] tokenHash;

  @Column(name = "family_id", nullable = false, updatable = false)
  private UUID familyId;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "replaced_by")
  private UUID replacedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected RefreshToken() {}

  static RefreshToken issue(
      UUID userId, byte[] tokenHash, UUID familyId, Instant now, Instant expiresAt) {
    RefreshToken token = new RefreshToken();
    token.id = UUID.randomUUID();
    token.userId = userId;
    token.tokenHash = tokenHash;
    token.familyId = familyId;
    token.createdAt = now;
    token.expiresAt = expiresAt;
    return token;
  }

  boolean isUsable(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  /** True when this token was already rotated or revoked; presenting it again means theft. */
  boolean wasRetired() {
    return revokedAt != null;
  }

  void rotateTo(UUID successorId, Instant now) {
    revokedAt = now;
    replacedBy = successorId;
  }

  public UUID id() {
    return id;
  }

  public UUID userId() {
    return userId;
  }

  public UUID familyId() {
    return familyId;
  }

  public Instant revokedAt() {
    return revokedAt;
  }
}
