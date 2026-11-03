package tz.co.pharmacy.modules.catalogue.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.catalogue.domain.Manufacturer;

/** Row-level security limits every query to the current tenant. */
public interface ManufacturerRepository extends JpaRepository<Manufacturer, Long> {

  Optional<Manufacturer> findByUid(UUID uid);

  List<Manufacturer> findAllByOrderByNameAsc();

  boolean existsByNameIgnoreCase(String name);

  boolean existsByNameIgnoreCaseAndUidNot(String name, UUID uid);
}
