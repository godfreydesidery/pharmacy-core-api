package tz.co.pharmacy.modules.inventory.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.inventory.domain.Batch;

public interface BatchRepository extends JpaRepository<Batch, Long> {

  Optional<Batch> findByUid(UUID uid);

  /**
   * The product's batches with this number in any case (STD-18), the exact spelling first, then the
   * first recorded: batches recorded before the rule may differ only in case (migration V12).
   */
  @Query(
      "select b from Batch b where b.productUid = :productUid"
          + " and lower(b.batchNumber) = lower(:batchNumber)"
          + " order by case when b.batchNumber = :batchNumber then 0 else 1 end, b.id")
  List<Batch> findAnyCase(
      @Param("productUid") UUID productUid, @Param("batchNumber") String batchNumber);

  /** The product's batch with this number, compared without regard to case. */
  default Optional<Batch> numbered(UUID productUid, String batchNumber) {
    return findAnyCase(productUid, batchNumber).stream().findFirst();
  }

  List<Batch> findByProductUidOrderByExpiryDateDescIdDesc(UUID productUid);
}
