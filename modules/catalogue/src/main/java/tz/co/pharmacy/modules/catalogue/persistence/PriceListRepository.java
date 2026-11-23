package tz.co.pharmacy.modules.catalogue.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.catalogue.domain.PriceList;

/** Row-level security limits every query to the current tenant. */
public interface PriceListRepository extends JpaRepository<PriceList, Long> {

  @EntityGraph(attributePaths = "items")
  Optional<PriceList> findByUid(UUID uid);

  @EntityGraph(attributePaths = "items")
  List<PriceList> findAllByOrderByNameAsc();

  // The plans alone, without their items (PERF-11): the till prices a sale from the plan's rule
  // and the items of the sale's products only (PriceListItemRepository.itemsOf).

  @Query("select l from PriceList l where l.uid = :uid")
  Optional<PriceList> planByUid(@Param("uid") UUID uid);

  @Query("select l from PriceList l order by l.name")
  List<PriceList> plansByName();

  @Query("select l from PriceList l where l.customerUid = :customer")
  List<PriceList> plansOfCustomer(@Param("customer") UUID customerUid);

  @Query("select l from PriceList l where l.customerType = :type")
  List<PriceList> plansOfType(@Param("type") String customerType);

  @Query("select l from PriceList l where l.payerUid = :payer")
  List<PriceList> plansOfPayer(@Param("payer") UUID payerUid);

  boolean existsByNameIgnoreCase(String name);

  boolean existsByNameIgnoreCaseAndUidNot(String name, UUID uid);
}
