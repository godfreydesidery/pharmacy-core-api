package tz.co.pharmacy.modules.sales.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.sales.domain.SaleReturn;

public interface SaleReturnRepository
    extends JpaRepository<SaleReturn, Long>, JpaSpecificationExecutor<SaleReturn> {

  /** The sale's earlier returns, oldest first: what they refunded, and how. */
  java.util.List<SaleReturn> findBySaleUidOrderByIdAsc(java.util.UUID saleUid);
}
