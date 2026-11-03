package tz.co.pharmacy.modules.notifications.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.notifications.domain.Preference;

public interface PreferenceRepository extends JpaRepository<Preference, Long> {

  List<Preference> findByUserUid(UUID userUid);

  Optional<Preference> findByUserUidAndCategory(UUID userUid, String category);
}
