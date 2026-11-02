package dev.commerceflow.identity.token;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /**
   * Row lock: two concurrent refreshes with the same token are serialised, so exactly one wins and
   * the other is treated as reuse.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from RefreshToken t where t.tokenHash = :hash")
  Optional<RefreshToken> findForUpdateByHash(@Param("hash") byte[] hash);

  @Modifying(clearAutomatically = true)
  @Query(
      "update RefreshToken t set t.revokedAt = :now"
          + " where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

  @Modifying
  @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
