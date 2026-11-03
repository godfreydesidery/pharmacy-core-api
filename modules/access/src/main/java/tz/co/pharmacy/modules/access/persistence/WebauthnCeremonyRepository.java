package tz.co.pharmacy.modules.access.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.access.domain.WebauthnCeremony;

public interface WebauthnCeremonyRepository extends JpaRepository<WebauthnCeremony, Long> {

  Optional<WebauthnCeremony> findByUid(UUID uid);
}
