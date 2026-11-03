package tz.co.pharmacy.modules.inventory.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.inventory.domain.Transfer;

public interface TransferRepository
    extends JpaRepository<Transfer, Long>, JpaSpecificationExecutor<Transfer> {

  Optional<Transfer> findByUid(UUID uid);

  /** The transfer, locked, so that it is received once only. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from Transfer t where t.uid = :uid")
  Optional<Transfer> lockByUid(UUID uid);

  /** Units of the product dispatched and not yet received, between any branches. */
  @Query(
      "select coalesce(sum(l.quantity), 0) from Transfer t join t.lines l"
          + " where l.productUid = :productUid"
          + " and t.status = tz.co.pharmacy.modules.inventory.ports.inbound.TransferStatus.IN_TRANSIT")
  long quantityInTransit(UUID productUid);
}
