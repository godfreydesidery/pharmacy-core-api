package tz.co.pharmacy.modules.catalogue.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.catalogue.domain.Promotion;

/** Row-level security limits every query to the current tenant. */
public interface PromotionRepository extends JpaRepository<Promotion, Long> {

  Optional<Promotion> findByUid(UUID uid);

  List<Promotion> findAllByOrderByValidFromDesc();

  @Query(
      "select p from Promotion p where p.active = true and p.validFrom <= :day"
          + " and (p.validTo is null or p.validTo >= :day)")
  List<Promotion> runningOn(LocalDate day);
}
