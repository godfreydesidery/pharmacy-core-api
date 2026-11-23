package tz.co.pharmacy.modules.finance.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.finance.domain.SupplierPayment;

/** Row-level security limits every query to the current tenant. */
public interface SupplierPaymentRepository
    extends JpaRepository<SupplierPayment, Long>, JpaSpecificationExecutor<SupplierPayment> {

  /** What payments have settled so far, per goods receipt: {@code [receiptUid, sum]} rows. */
  @Query(
      "select a.goodsReceiptUid, sum(a.amount) from SupplierPaymentAllocation a"
          + " where a.goodsReceiptUid in :receiptUids group by a.goodsReceiptUid")
  List<Object[]> settledOf(@Param("receiptUids") Collection<UUID> receiptUids);
}
