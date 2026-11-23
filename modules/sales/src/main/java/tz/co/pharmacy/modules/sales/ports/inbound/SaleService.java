package tz.co.pharmacy.modules.sales.ports.inbound;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Sales at the current branch ({@code ExecutionContext.requireBranch()}) for writes and lists. */
public interface SaleService {

  /**
   * The next invoice number, such as {@code INV-HQ-000123}, inside the caller's transaction. The
   * branch's number row stays locked until that transaction ends (gapless numbering, see {@code
   * DocumentNumbers}), so the caller takes it after every call out and holds the transaction only
   * for database work (PERF-1).
   */
  String nextSaleNumber();

  /**
   * Runs before the sale's transaction and outside any (PERF-1): asks the payment providers about
   * every push request among {@code payments} that they have not confirmed yet, so that {@link
   * #record} makes no call out while it holds the sale number and the stock. Refuses 422 {@code
   * payment_declined} for a request still waiting for the customer; every other decision is {@link
   * #record}'s.
   */
  void preparePayments(List<PaymentCommand> payments);

  /**
   * Issues the sale's fiscal receipt when it is still due, after the sale committed and in
   * transactions of its own (PERF-1): takes a short lease on the sale, calls the fiscal port with
   * no transaction open, then records the reference. Does nothing when the receipt was issued or
   * another caller holds the lease. A failure of the port leaves the receipt due and is thrown, so
   * the {@code sale.completed} reaction that calls this is delivered again by the outbox.
   */
  void fiscalise(UUID saleUid);

  Optional<SaleView> findByIdempotencyKey(String key);

  /**
   * Takes the idempotency key for the caller's transaction, then returns the sale already made with
   * it, if any. A second request with the same key, sent before the first has finished (a double
   * tap, a till retrying after a timeout), waits for the first and gets its sale instead of a
   * conflict.
   */
  Optional<SaleView> claimIdempotencyKey(String key);

  /**
   * As {@link #claimIdempotencyKey(String)}, and the sale found must have been made by the same
   * request: {@code requestFingerprint} (SHA-256 of the canonical request, the key left out) equal
   * to the one the sale keeps. Otherwise 422 {@code idempotency_key_reused} (members {@code
   * saleUid}, {@code saleNumber}): the key already made a sale from a different cart. A sale made
   * before fingerprints were kept answers as before.
   */
  Optional<SaleView> claimIdempotencyKey(String key, String requestFingerprint);

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
   * sale.completed}. The fiscal receipt is not issued here: the sale is saved with it due ({@link
   * SaleView#fiscalDue()}) and {@link #fiscalise} issues it after commit.
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
