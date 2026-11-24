package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Payments to suppliers, which settle accounts payable and realise exchange differences. */
public interface SupplierPayments {

  /** Records the payment and posts its journal entry in the same transaction. */
  SupplierPaymentView record(SupplierPaymentCommand command);

  Page<SupplierPaymentView> list(UUID supplierUid, PageQuery query);

  /**
   * Per goods receipt, in its currency, what payments have settled of it (receipts with none are
   * absent): with the credited returns, what is no longer open of it.
   */
  Map<UUID, BigDecimal> settledOf(Collection<UUID> receiptUids);
}
