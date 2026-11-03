package tz.co.pharmacy.modules.inventory.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.inventory.domain.SerialUnit;
import tz.co.pharmacy.modules.inventory.ports.inbound.SerialStatus;

/** Row-level security limits every query to the current tenant. */
public interface SerialUnitRepository extends JpaRepository<SerialUnit, Long> {

  /** The named units of a product, locked for the rest of the transaction. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select u from SerialUnit u join fetch u.batch"
          + " where u.productUid = :productUid and u.serialNumber in :serials")
  List<SerialUnit> lockNamed(java.util.UUID productUid, Collection<String> serials);

  /** The branch's units of a product in a status, oldest first, locked. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select u from SerialUnit u join fetch u.batch b"
          + " where u.branchUid = :branchUid and u.productUid = :productUid"
          + " and u.status = :status order by u.id")
  List<SerialUnit> lockAt(java.util.UUID branchUid, java.util.UUID productUid, SerialStatus status);

  boolean existsByProductUidAndSerialNumberIn(
      java.util.UUID productUid, Collection<String> serials);

  @Query(
      "select u from SerialUnit u join fetch u.batch b"
          + " where u.branchUid = :branchUid and u.productUid = :productUid"
          + " and (:status is null or u.status = :status) order by u.serialNumber")
  List<SerialUnit> atBranch(
      java.util.UUID branchUid, java.util.UUID productUid, SerialStatus status);
}
