package tz.co.pharmacy.modules.catalogue.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.catalogue.domain.PackLink;

/** Row-level security limits every query to the current tenant. */
public interface PackLinkRepository extends JpaRepository<PackLink, Long> {

  Optional<PackLink> findByUnitProductUid(UUID unitProductUid);

  List<PackLink> findAllByOrderByIdAsc();
}
