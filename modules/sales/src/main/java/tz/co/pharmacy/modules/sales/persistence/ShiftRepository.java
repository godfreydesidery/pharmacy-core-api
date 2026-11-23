package tz.co.pharmacy.modules.sales.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.sales.domain.Shift;

/** Row-level security limits every query to the current tenant. */
public interface ShiftRepository
    extends JpaRepository<Shift, Long>, JpaSpecificationExecutor<Shift> {

  /** The cashier's shift by account (who opened it), whatever email they sign in with. */
  Optional<Shift> findByBranchUidAndCreatedByUidAndStatus(
      UUID branchUid, UUID cashierUid, String status);

  /**
   * The cashier's open shift, share-locked until the transaction ends (FOR SHARE): a sale or refund
   * taken in it commits before the shift can close, so the close counts it.
   */
  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query(
      "select s from Shift s where s.branchUid = :branch and s.createdByUid = :cashier"
          + " and s.status = 'OPEN'")
  Optional<Shift> shareOpen(@Param("branch") UUID branchUid, @Param("cashier") UUID cashierUid);

  /** The cashier's open shift, locked for its close (FOR UPDATE): waits for sales in progress. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select s from Shift s where s.branchUid = :branch and s.createdByUid = :cashier"
          + " and s.status = 'OPEN'")
  Optional<Shift> lockOpen(@Param("branch") UUID branchUid, @Param("cashier") UUID cashierUid);

  List<Shift> findTop100ByBranchUidOrderByIdDesc(UUID branchUid);
}
