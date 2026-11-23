package tz.co.pharmacy.modules.access.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.access.domain.Passkey;

public interface PasskeyRepository extends JpaRepository<Passkey, Long> {

  Optional<Passkey> findByCredentialId(String credentialId);

  Optional<Passkey> findByUidAndUserUid(UUID uid, UUID userUid);

  List<Passkey> findAllByUserUidOrderByIdDesc(UUID userUid);

  List<Passkey> findAllByUserHandle(String userHandle);

  int countByUserUid(UUID userUid);

  boolean existsByUserUid(UUID userUid);

  /** Removes every passkey of the user, as a password reset or a recovery does. */
  long deleteByUserUid(UUID userUid);
}
