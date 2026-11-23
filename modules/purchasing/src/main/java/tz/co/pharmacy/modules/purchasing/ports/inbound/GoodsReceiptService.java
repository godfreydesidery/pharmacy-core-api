package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Goods receipts of the current branch. */
public interface GoodsReceiptService {

  /**
   * Records the receipt (GRN-&lt;branchCode&gt;-000001), checking it against its purchase order
   * (same branch and supplier, still open, no more than outstanding: 422 over_receipt), updates the
   * order and publishes goods.received. Stock is not touched here: see the receiving service.
   */
  GoodsReceiptView record(RecordReceiptCommand command);

  Optional<GoodsReceiptView> find(UUID uid);

  Page<GoodsReceiptView> list(UUID supplierUid, PageQuery query);

  /**
   * The total received at the branches (null = all) from {@code from} to {@code to}: the pharmacy's
   * local dates, in its time zone.
   */
  BigDecimal purchasesTotal(Set<UUID> branchUids, LocalDate from, LocalDate to);

  /** The current tenant's receipts with these uids, at any branch (for supplier payments). */
  java.util.List<GoodsReceiptView> findAll(java.util.Collection<java.util.UUID> uids);
}
