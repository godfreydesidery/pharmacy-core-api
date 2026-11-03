package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Purchase orders of the current branch. */
public interface PurchaseOrderService {

  /** Number PO-&lt;branchCode&gt;-000001; status ORDERED. */
  PurchaseOrderView create(CreatePurchaseOrderCommand command);

  /** 422 not_cancellable unless ORDERED. */
  PurchaseOrderView cancel(UUID uid, String reason);

  Optional<PurchaseOrderView> find(UUID uid);

  Page<PurchaseOrderView> list(PurchaseOrderStatus status, UUID supplierUid, PageQuery query);
}
