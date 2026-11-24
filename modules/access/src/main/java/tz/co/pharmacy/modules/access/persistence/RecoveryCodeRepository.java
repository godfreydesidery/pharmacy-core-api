package tz.co.pharmacy.modules.access.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.access.domain.RecoveryCode;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

  /** An unused code of the user stored as one of {@code codeHashes} (its keyed or legacy hash). */
  Optional<RecoveryCode> findFirstByUserUidAndCodeHashInAndUsedAtIsNull(
      UUID userUid, java.util.Collection<String> codeHashes);

  int countByUserUidAndUsedAtIsNull(UUID userUid);

  @Modifying
  @Query("delete from RecoveryCode r where r.userUid = :userUid")
  int deleteAllOf(@Param("userUid") UUID userUid);
}
