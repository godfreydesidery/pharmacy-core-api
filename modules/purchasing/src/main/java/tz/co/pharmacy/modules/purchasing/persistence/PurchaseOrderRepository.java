package tz.co.pharmacy.modules.purchasing.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.purchasing.domain.PurchaseOrder;

public interface PurchaseOrderRepository
    extends JpaRepository<PurchaseOrder, Long>, JpaSpecificationExecutor<PurchaseOrder> {

  Optional<PurchaseOrder> findByUid(UUID uid);

  /** The order, locked, so that two receipts against it cannot both take its outstanding units. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from PurchaseOrder o where o.uid = :uid")
  Optional<PurchaseOrder> lockByUid(UUID uid);
}
