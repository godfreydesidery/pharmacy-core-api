package tz.co.pharmacy.modules.sales.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.sales.domain.SaleReturn;

public interface SaleReturnRepository
    extends JpaRepository<SaleReturn, Long>, JpaSpecificationExecutor<SaleReturn> {

  /** The sale's earlier returns, oldest first: what they refunded, and how. */
  List<SaleReturn> findBySaleUidOrderByIdAsc(UUID saleUid);

  /** The return, locked until the transaction ends: two releases of one line take turns. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from SaleReturn r where r.uid = :uid")
  Optional<SaleReturn> lockByUid(@Param("uid") UUID uid);

  /** The branch's returns with units still held in quarantine, newest first. */
  @Query(
      "select r from SaleReturn r where r.branchUid = :branch and exists (select 1 from"
          + " SaleReturnLine l where l.saleReturn = r and l.heldQuantity > 0) order by r.id desc")
  List<SaleReturn> findHeld(@Param("branch") UUID branchUid, Pageable page);
}
