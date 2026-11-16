package tz.co.pharmacy.modules.catalogue.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.catalogue.domain.PriceList;

/** Row-level security limits every query to the current tenant. */
public interface PriceListRepository extends JpaRepository<PriceList, Long> {

  @EntityGraph(attributePaths = "items")
  Optional<PriceList> findByUid(UUID uid);

  @EntityGraph(attributePaths = "items")
  List<PriceList> findAllByOrderByNameAsc();

  @EntityGraph(attributePaths = "items")
  List<PriceList> findByCustomerUid(UUID customerUid);

  @EntityGraph(attributePaths = "items")
  List<PriceList> findByCustomerType(String customerType);

  boolean existsByNameIgnoreCase(String name);

  boolean existsByNameIgnoreCaseAndUidNot(String name, UUID uid);
}
