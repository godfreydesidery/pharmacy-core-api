package tz.co.pharmacy.modules.finance.ports.inbound;

import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Payments to suppliers, which settle accounts payable and realise exchange differences. */
public interface SupplierPayments {

  /** Records the payment and posts its journal entry in the same transaction. */
  SupplierPaymentView record(SupplierPaymentCommand command);

  Page<SupplierPaymentView> list(UUID supplierUid, PageQuery query);
}
