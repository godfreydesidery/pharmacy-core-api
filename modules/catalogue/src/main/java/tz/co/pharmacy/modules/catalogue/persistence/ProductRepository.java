package tz.co.pharmacy.modules.catalogue.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.catalogue.domain.Product;

/** Row-level security limits every query to the current tenant. */
public interface ProductRepository
    extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

  @EntityGraph(attributePaths = {"category", "manufacturer"})
  Optional<Product> findByUid(UUID uid);

  @EntityGraph(attributePaths = {"category", "manufacturer"})
  List<Product> findByUidIn(Collection<UUID> uids);

  boolean existsBySkuIgnoreCase(String sku);

  Optional<Product> findBySkuIgnoreCase(String sku);

  boolean existsBySkuIgnoreCaseAndUidNot(String sku, UUID uid);

  boolean existsByGtin(String gtin);

  boolean existsByGtinAndUidNot(String gtin, UUID uid);

  /** Every product CONTROLLED now or at any time before, by name. */
  @org.springframework.data.jpa.repository.Query(
      "select p from Product p where p.everControlled = true or p.schedule ="
          + " tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule.CONTROLLED"
          + " order by lower(p.name), p.id")
  java.util.List<Product> findControlledEver();
}
