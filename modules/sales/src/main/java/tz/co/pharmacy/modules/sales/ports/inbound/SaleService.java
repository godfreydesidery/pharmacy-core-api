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
   * Records a return against a sale of the current branch. Refuses 422 {@code return_exceeds_sold}.
   * Publishes {@code sale.returned}.
   */
  SaleReturnView recordReturn(RecordReturnCommand command);

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
