package tz.co.pharmacy.modules.purchasing.application;

import tz.co.pharmacy.modules.purchasing.domain.GoodsReceipt;
import tz.co.pharmacy.modules.purchasing.domain.PurchaseOrder;
import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderLineView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReceiptLineView;
import tz.co.pharmacy.platform.kernel.Amounts;

/** Maps the module's entities to its views. */
final class PurchasingViews {

  private PurchasingViews() {}

  /** A unit cost in the base currency, kept to 4 decimals so stock valuation does not drift. */
  static java.math.BigDecimal baseUnitCost(
      java.math.BigDecimal unitCost, java.math.BigDecimal exchangeRate) {
    return unitCost
        .multiply(exchangeRate)
        .setScale(4, tz.co.pharmacy.platform.kernel.Amounts.ROUNDING);
  }

  static PurchaseOrderView order(PurchaseOrder order) {
    return new PurchaseOrderView(
        order.getUid(),
        order.getNumber(),
        order.getBranchUid(),
        order.getSupplierUid(),
        order.getSupplierName(),
        order.getStatus(),
        order.getExpectedDate(),
        order.getNote(),
        order.getTotal(),
        order.getLines().stream()
            .map(
                line ->
                    new PurchaseOrderLineView(
                        line.getUid(),
                        line.getProductUid(),
                        line.getProductName(),
                        line.getQuantity(),
                        line.getReceivedQuantity(),
                        line.getUnitCost(),
                        line.getLineTotal()))
            .toList(),
        order.getCreatedAt(),
        order.getCreatedBy(),
        order.getCurrency(),
        order.getExchangeRate().stripTrailingZeros(),
        order.getBaseTotal());
  }

  static GoodsReceiptView receipt(GoodsReceipt receipt) {
    return new GoodsReceiptView(
        receipt.getUid(),
        receipt.getNumber(),
        receipt.getBranchUid(),
        receipt.getPurchaseOrderUid(),
        receipt.getPurchaseOrderNumber(),
        receipt.getSupplierUid(),
        receipt.getSupplierName(),
        receipt.getSupplierInvoiceNumber(),
        receipt.getNote(),
        receipt.getTotal(),
        receipt.getLines().stream()
            .map(
                line ->
                    new ReceiptLineView(
                        line.getProductUid(),
                        line.getProductName(),
                        line.getBatchNumber(),
                        line.getExpiryDate(),
                        line.getQuantity(),
                        line.getUnitCost(),
                        line.getLineTotal(),
                        baseUnitCost(line.getUnitCost(), receipt.getExchangeRate()),
                        line.getSerialNumbers()))
            .toList(),
        receipt.getCreatedAt(),
        receipt.getCreatedBy(),
        receipt.getCurrency(),
        receipt.getExchangeRate().stripTrailingZeros(),
        receipt.getBaseTotal(),
        receipt.getVatTotal() == null ? Amounts.ZERO : receipt.getVatTotal(),
        receipt.getBaseVatTotal() == null ? Amounts.ZERO : receipt.getBaseVatTotal());
  }
}
