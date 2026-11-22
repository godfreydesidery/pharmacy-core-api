package tz.co.pharmacy.modules.access.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.access.domain.PhoneCode;

public interface PhoneCodeRepository extends JpaRepository<PhoneCode, Long> {

  /** The latest code of that purpose sent to the account. */
  Optional<PhoneCode> findFirstByUserUidAndPurposeOrderByCreatedAtDesc(
      UUID userUid, String purpose);

  /** Codes sent to the account for that purpose since {@code since}, to limit resends. */
  long countByUserUidAndPurposeAndCreatedAtAfter(UUID userUid, String purpose, Instant since);
}
