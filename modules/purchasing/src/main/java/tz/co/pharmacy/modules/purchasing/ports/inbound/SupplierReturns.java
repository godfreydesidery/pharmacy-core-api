package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * Returns to suppliers (debit notes) of the current branch. Recording one publishes
 * supplier.returned; the stock it takes out is the caller's (see the receiving service). A return
 * is PENDING_CREDIT until the supplier's credit note is recorded ({@link #credit}), and only then
 * lowers what is owed to the supplier and what is open on its goods receipt (FRD-16).
 */
public interface SupplierReturns {

  /**
   * Records the return (RTS-&lt;branchCode&gt;-000001) of the given, already valued lines; 422
   * supplier_not_found, goods_receipt_not_found, receipt_supplier_mismatch; 403
   * supplier_return_needs_receipt when no goods receipt is named and the caller lacks
   * purchasing.manage.
   */
  SupplierReturnView record(RecordSupplierReturnCommand command);

  /**
   * Newest first, 100 at most; {@code supplierUid} null for every supplier, {@code status}
   * (PENDING_CREDIT, CREDITED) null for both.
   */
  Page<SupplierReturnView> list(UUID supplierUid, String status, PageQuery query);

  /** 404 supplier_return_not_found. */
  SupplierReturnView get(UUID uid);

  /**
   * Records the supplier's credit note for a pending return, at any branch the caller may use: its
   * amount is in the goods receipt's currency (the base currency for a return without one). Of it,
   * what is still open on the receipt comes off the receipt (a later payment cannot settle it
   * again); the rest is credit on the supplier's account. Publishes supplier.credited. 404
   * supplier_return_not_found; 409 return_already_credited; 422 invalid_amount,
   * credit_exceeds_receipt (more than the receipt's total), duplicate_credit_note (the supplier's
   * note is already recorded), future_date.
   */
  SupplierReturnView credit(UUID returnUid, CreditNoteCommand command);

  /**
   * Per goods receipt, what credited returns took off its open amount (in its currency); receipts
   * with none are absent.
   */
  Map<UUID, BigDecimal> creditedAgainst(Collection<UUID> receiptUids);

  record RecordSupplierReturnCommand(
      UUID supplierUid, UUID goodsReceiptUid, String reason, String note, List<Line> lines) {}

  /** The supplier's credit note: its number, date (not in the future) and amount. */
  record CreditNoteCommand(String number, LocalDate date, BigDecimal amount) {}

  /** A batch and quantity going back, with the batch's unit cost in the base currency. */
  record Line(
      UUID productUid,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitCost) {}

  /**
   * A return. {@code total} is its value at cost in base. {@code credit} is null while it is
   * PENDING_CREDIT, and for returns from before credit notes were recorded (they came off what was
   * owed when they were made).
   */
  record SupplierReturnView(
      UUID uid,
      String number,
      UUID branchUid,
      UUID supplierUid,
      String supplierName,
      UUID goodsReceiptUid,
      String goodsReceiptNumber,
      String reason,
      String note,
      BigDecimal total,
      Instant createdAt,
      String createdBy,
      String createdByName,
      List<LineView> lines,
      String status,
      CreditNoteView credit) {}

  /**
   * The supplier's credit note: {@code amount} in {@code currency}, {@code baseAmount} in base,
   * {@code appliedToReceipt} what came off the goods receipt's open amount (receipt currency).
   */
  record CreditNoteView(
      String number,
      LocalDate date,
      String currency,
      BigDecimal amount,
      BigDecimal baseAmount,
      BigDecimal appliedToReceipt,
      Instant recordedAt,
      String recordedBy) {}

  record LineView(
      UUID productUid,
      String productName,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitCost,
      BigDecimal lineTotal) {}
}
