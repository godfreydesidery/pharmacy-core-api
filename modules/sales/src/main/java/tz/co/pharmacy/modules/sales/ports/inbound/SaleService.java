package tz.co.pharmacy.modules.sales.ports.inbound;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Sales at the current branch ({@code ExecutionContext.requireBranch()}) for writes and lists. */
public interface SaleService {

  /** The next invoice number, such as {@code INV-HQ-000123}, inside the caller's transaction. */
  String nextSaleNumber();

  Optional<SaleView> findByIdempotencyKey(String key);

  /**
   * Takes the idempotency key for the caller's transaction, then returns the sale already made with
   * it, if any. A second request with the same key, sent before the first has finished (a double
   * tap, a till retrying after a timeout), waits for the first and gets its sale instead of a
   * conflict.
   */
  Optional<SaleView> claimIdempotencyKey(String key);

  /**
   * Sales per pricing plan between two moments (the branches the caller reports on): how many, what
   * they charged, what they would have cost at Normal Sale prices, and the difference (positive:
   * given away against Normal Sale). Sales before pricing plans have no normal total.
   */
  List<PlanSalesView> byPricingPlan(java.time.Instant from, java.time.Instant to);

  record PlanSalesView(
      String plan,
      long sales,
      java.math.BigDecimal total,
      java.math.BigDecimal normalTotal,
      java.math.BigDecimal belowNormal) {}

  /**
   * Records a priced sale. Per line: gross = unitPrice × quantity, discount = gross ×
   * discountPercent %, tax = (gross − discount) × taxRate %, lineTotal = gross − discount + tax.
   * Refuses 422 {@code prescription_required}, {@code discount_not_allowed}, {@code
   * customer_required}, {@code payment_short}, {@code payment_declined}. Publishes {@code
   * sale.completed}.
   */
  SaleView record(RecordSaleCommand command);

  Optional<SaleView> find(UUID uid);

  Page<SaleSummaryView> list(SaleFilter filter, PageQuery query);

  /** The newest sales of the branches, null for all, newest first. */
  List<SaleSummaryView> recent(Set<UUID> branchUids, int limit);

  /**
   * Records a return against a sale of the current branch. Refuses 422 {@code return_exceeds_sold},
   * {@code restock_not_allowed} (a prescription or controlled medicine to SHELF); 409 {@code
   * shift_required} when the pharmacy requires an open shift and the caller has none here; 403
   * {@code approval_required} for a return after the pharmacy's return window (LATE_RETURN, which
   * also covers the amount) or, within it, a refund above the approval limit (REFUND). The caller
   * moves the stock by each line's disposition. Publishes {@code sale.returned}.
   */
  SaleReturnView recordReturn(RecordReturnCommand command);

  /**
   * Releases the units a return line holds in quarantine, all of them, to {@code outcome} (SHELF or
   * DAMAGED). 404 return_not_found, return_line_not_found; 422 sale_other_branch, nothing_held,
   * invalid_outcome, restock_not_allowed (a prescription or controlled medicine to SHELF). The
   * caller moves the stock ({@link ReleasedLine}). Publishes {@code sale.return_released}.
   */
  ReleasedLine releaseReturnLine(UUID returnUid, UUID lineUid, ReturnDisposition outcome);

  /** What a release frees: the units to restock under {@code reference}, and the return now. */
  record ReleasedLine(
      SaleReturnView saleReturn,
      UUID productUid,
      UUID batchUid,
      int quantity,
      List<String> serialNumbers,
      String reference,
      ReturnDisposition outcome) {}

  /** The branch's returns with units still held in quarantine, newest first (at most 200). */
  List<SaleReturnView> heldReturns();

  Page<SaleReturnView> returns(PageQuery query);

  /**
   * The total and the base value of the payments a sale would have, without recording it and
   * without refusing a short or excess payment (see {@link SalePreview}).
   */
  SalePreview preview(
      java.util.List<SaleLineCommand> lines,
      java.math.BigDecimal discountPercent,
      java.util.List<PaymentCommand> payments);
}
