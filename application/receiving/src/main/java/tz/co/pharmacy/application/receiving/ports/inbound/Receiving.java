package tz.co.pharmacy.application.receiving.ports.inbound;

import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.RecordReceiptCommand;

/** Receives goods at the current branch: the receipt and the stock it brings, together. */
public interface Receiving {

  GoodsReceiptView receive(RecordReceiptCommand command);

  /**
   * Sends stock of batches at the current branch back to their supplier: purchasing records the
   * return (the debit note) at each batch's cost, then inventory takes the stock out under its
   * number. 422 batch_not_in_stock, insufficient_stock and purchasing's refusals.
   */
  tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierReturns.SupplierReturnView
      returnToSupplier(ReturnToSupplierCommand command);

  record ReturnToSupplierCommand(
      java.util.UUID supplierUid,
      java.util.UUID goodsReceiptUid,
      String reason,
      String note,
      java.util.List<ReturnLine> lines) {}

  record ReturnLine(java.util.UUID productUid, java.util.UUID batchUid, Integer quantity) {}
}
