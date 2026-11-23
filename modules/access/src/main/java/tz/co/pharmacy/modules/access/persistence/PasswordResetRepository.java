package tz.co.pharmacy.modules.access.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.access.domain.PasswordReset;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {

  Optional<PasswordReset> findByTokenHash(String tokenHash);

  /**
   * Ends every open reset link of the user: a newer link, a completed reset, a new password or a
   * new email supersedes them (adversarial review AUTH-12).
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "update PasswordReset r set r.usedAt = :at where r.userUid = :user and r.usedAt is null")
  int closeOpen(
      @org.springframework.data.repository.query.Param("user") java.util.UUID user,
      @org.springframework.data.repository.query.Param("at") java.time.Instant at);

  /** Reset links asked for since {@code after}, to cap them per account. */
  long countByUserUidAndCreatedAtAfter(java.util.UUID userUid, java.time.Instant after);
}
