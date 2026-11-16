package tz.co.pharmacy.modules.inventory.persistence;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.inventory.domain.Batch;
import tz.co.pharmacy.modules.inventory.domain.StockLevel;

public interface StockLevelRepository extends JpaRepository<StockLevel, Long> {

  /**
   * The branch's sellable stock of a product, locked for the rest of the transaction, earliest
   * expiry first (FEFO), or first in, first out for batches without expiry: two tills selling the
   * last units wait for each other instead of both taking them.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select s from StockLevel s join fetch s.batch b"
          + " where s.branchUid = :branchUid and s.productUid = :productUid"
          + " and s.quantity > 0 and (b.expiryDate is null or b.expiryDate >= :today)"
          + " and b.quarantined = false"
          + " order by b.expiryDate asc nulls last, b.id")
  List<StockLevel> lockSellable(UUID branchUid, UUID productUid, LocalDate today);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from StockLevel s where s.branchUid = :branchUid and s.batch = :batch")
  Optional<StockLevel> lockAt(UUID branchUid, Batch batch);

  @Query(
      "select coalesce(sum(s.quantity), 0) from StockLevel s"
          + " where s.branchUid = :branchUid and s.productUid = :productUid")
  long quantityAt(UUID branchUid, UUID productUid);

  /** Every batch on hand at the branch, for a count sheet. */
  @Query(
      "select s from StockLevel s join fetch s.batch b where s.branchUid = :branchUid"
          + " and s.quantity > 0 order by s.productUid, b.batchNumber")
  List<StockLevel> allOnHand(UUID branchUid);

  /** The batch's quantity at every branch of the tenant. */
  @Query("select coalesce(sum(s.quantity), 0) from StockLevel s where s.batch = :batch")
  long quantityOfBatch(Batch batch);

  /** The product's quantity at every branch of the tenant, expired batches included. */
  @Query("select coalesce(sum(s.quantity), 0) from StockLevel s where s.productUid = :productUid")
  long quantityEverywhere(UUID productUid);

  @Query(
      "select s from StockLevel s join fetch s.batch b"
          + " where s.branchUid = :branchUid and s.productUid = :productUid and s.quantity > 0"
          + " order by b.expiryDate asc nulls last, b.id")
  List<StockLevel> inStock(UUID branchUid, UUID productUid);

  /** Each product's unexpired quantity at the branch: rows of [productUid, quantity]. */
  @Query(
      "select s.productUid, sum(s.quantity) from StockLevel s join s.batch b"
          + " where s.branchUid = :branchUid and s.productUid in :productUids"
          + " and (b.expiryDate is null or b.expiryDate >= :today) and b.quarantined = false"
          + " group by s.productUid")
  List<Object[]> sellableQuantities(UUID branchUid, Collection<UUID> productUids, LocalDate today);
}
