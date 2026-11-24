package tz.co.pharmacy.modules.purchasing.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.purchasing.domain.SupplierReturn;

/** Row-level security limits every query to the current tenant. */
public interface SupplierReturnRepository
    extends JpaRepository<SupplierReturn, Long>, JpaSpecificationExecutor<SupplierReturn> {

  @EntityGraph(attributePaths = "lines")
  Optional<SupplierReturn> findByUid(UUID uid);

  /** The return, locked, so that two credit notes for it cannot both be recorded. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from SupplierReturn r where r.uid = :uid")
  Optional<SupplierReturn> lockByUid(@Param("uid") UUID uid);

  @EntityGraph(attributePaths = "lines")
  List<SupplierReturn> findTop100ByBranchUidOrderByIdDesc(UUID branchUid);

  @EntityGraph(attributePaths = "lines")
  List<SupplierReturn> findTop100ByBranchUidAndSupplierUidOrderByIdDesc(
      UUID branchUid, UUID supplierUid);

  /** Whether the supplier's credit note of this number (any case) is already recorded. */
  @Query(
      "select count(r) > 0 from SupplierReturn r where r.supplierUid = :supplier"
          + " and lower(r.creditNoteNumber) = lower(:number)")
  boolean creditNoteRecorded(@Param("supplier") UUID supplierUid, @Param("number") String number);

  /** What credited returns took off each receipt's open amount: {@code [receiptUid, sum]}. */
  @Query(
      "select r.goodsReceiptUid, sum(r.creditApplied) from SupplierReturn r"
          + " where r.goodsReceiptUid in :receipts and r.creditApplied > 0"
          + " group by r.goodsReceiptUid")
  List<Object[]> creditedAgainst(@Param("receipts") Collection<UUID> receiptUids);
}
