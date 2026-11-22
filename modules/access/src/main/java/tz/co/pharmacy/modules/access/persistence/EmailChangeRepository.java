package tz.co.pharmacy.modules.access.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.access.domain.EmailChange;

public interface EmailChangeRepository extends JpaRepository<EmailChange, Long> {

  Optional<EmailChange> findByTokenHash(String tokenHash);

  Optional<EmailChange> findByCancelTokenHash(String cancelTokenHash);

  /** The account's changes, newest first; the open ones are cancelled by a new request. */
  List<EmailChange> findByUserUidOrderByCreatedAtDesc(UUID userUid);
}
