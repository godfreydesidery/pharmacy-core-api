package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Returns to suppliers (debit notes) of the current branch. Recording one publishes
 * supplier.returned; the stock it takes out is the caller's (see the receiving service).
 */
public interface SupplierReturns {

  /**
   * Records the return (RTS-&lt;branchCode&gt;-000001) of the given, already valued lines; 422
   * supplier_not_found, goods_receipt_not_found, receipt_supplier_mismatch.
   */
  SupplierReturnView record(RecordSupplierReturnCommand command);

  /** Newest first, 100 at most; {@code supplierUid} null for every supplier. */
  List<SupplierReturnView> list(UUID supplierUid);

  /** 404 supplier_return_not_found. */
  SupplierReturnView get(UUID uid);

  record RecordSupplierReturnCommand(
      UUID supplierUid, UUID goodsReceiptUid, String reason, String note, List<Line> lines) {}

  /** A batch and quantity going back, with the batch's unit cost in the base currency. */
  record Line(
      UUID productUid,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitCost) {}

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
      List<LineView> lines) {}

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
