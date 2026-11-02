package dev.commerceflow.identity.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

  public enum Status {
    ACTIVE,
    DISABLED
  }

  @Id private UUID id;

  /** Always lower-cased; see {@link Emails#normalize}. */
  @Column(nullable = false)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  @Column(name = "failed_login_count", nullable = false)
  private int failedLoginCount;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Version private long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected User() {}

  public static User create(
      String normalizedEmail, String passwordHash, String displayName, Role role, Instant now) {
    User user = new User();
    user.id = UUID.randomUUID();
    user.email = normalizedEmail;
    user.passwordHash = passwordHash;
    user.displayName = displayName;
    user.role = role;
    user.status = Status.ACTIVE;
    user.createdAt = now;
    user.updatedAt = now;
    return user;
  }

  public boolean isLocked(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  public boolean canLogIn(Instant now) {
    return status == Status.ACTIVE && !isLocked(now);
  }

  public UUID id() {
    return id;
  }

  public String email() {
    return email;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public String displayName() {
    return displayName;
  }

  public Role role() {
    return role;
  }

  public int failedLoginCount() {
    return failedLoginCount;
  }

  public Instant lockedUntil() {
    return lockedUntil;
  }
}
