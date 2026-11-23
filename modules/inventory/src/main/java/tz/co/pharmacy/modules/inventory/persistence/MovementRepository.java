package tz.co.pharmacy.modules.inventory.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.inventory.domain.Movement;

public interface MovementRepository
    extends JpaRepository<Movement, Long>, JpaSpecificationExecutor<Movement> {

  /**
   * The value at cost of the stock that came back under a return's reference, each movement at the
   * cost the batch had when it was restocked.
   */
  @Query(
      "select coalesce(sum(m.quantityChange * m.unitCost), 0) from Movement m"
          + " where m.type = tz.co.pharmacy.modules.inventory.ports.inbound.MovementType.SALE_RETURN"
          + " and m.reference = :reference and m.unitCost is not null")
  java.math.BigDecimal restockedValue(String reference);

  /** Rows of [all SALE_RETURN movements under the reference, those without a recorded cost]. */
  @Query(
      "select count(m), sum(case when m.unitCost is null then 1 else 0 end) from Movement m"
          + " where m.type = tz.co.pharmacy.modules.inventory.ports.inbound.MovementType.SALE_RETURN"
          + " and m.reference = :reference")
  List<Object[]> restockedCount(String reference);
}
