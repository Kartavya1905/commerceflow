package dev.commerceflow.identity.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByEmail(String normalizedEmail);

  boolean existsByEmail(String normalizedEmail);

  boolean existsByRole(Role role);

  /**
   * Counts a failed login in one atomic statement, locking the account when the count reaches
   * {@code max}. A read-modify-write in Java would lose increments under concurrent guessing.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update User u
         set u.failedLoginCount = u.failedLoginCount + 1,
             u.lockedUntil = case when u.failedLoginCount + 1 >= :max then :lockUntil
                                  else u.lockedUntil end,
             u.updatedAt = :now
       where u.id = :id
      """)
  int recordFailedLogin(
      @Param("id") UUID id,
      @Param("max") int max,
      @Param("lockUntil") Instant lockUntil,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true)
  @Query(
      """
      update User u
         set u.failedLoginCount = 0, u.lockedUntil = null, u.updatedAt = :now
       where u.id = :id and (u.failedLoginCount > 0 or u.lockedUntil is not null)
      """)
  int resetFailedLogins(@Param("id") UUID id, @Param("now") Instant now);
}
