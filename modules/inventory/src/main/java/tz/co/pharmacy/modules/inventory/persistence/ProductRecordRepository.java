package tz.co.pharmacy.modules.inventory.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.inventory.domain.ProductRecord;

public interface ProductRecordRepository extends JpaRepository<ProductRecord, Long> {

  Optional<ProductRecord> findByProductUid(UUID productUid);

  /**
   * Holds, until the transaction ends, the lock that makes "find the product's snapshot, else
   * create it" one step: the catalogue's product event and a first stock entry for the same new
   * product otherwise both create it, and one fails on the unique key.
   */
  @Query(
      value =
          "select 1 from (select pg_advisory_xact_lock(hashtextextended(cast(:uid as text), 0)))"
              + " locked",
      nativeQuery = true)
  Integer lockSnapshot(@Param("uid") UUID productUid);
}
