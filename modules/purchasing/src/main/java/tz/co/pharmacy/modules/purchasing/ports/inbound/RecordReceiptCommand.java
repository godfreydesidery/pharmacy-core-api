package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * Goods received at the current branch; {@code purchaseOrderUid} is optional. {@code vatAmount}
 * (STD-2) is the input VAT on the supplier's invoice, in the receipt's currency, on top of the
 * lines' costs (which exclude it): what is owed is the lines plus the VAT, the stock is valued
 * without it and the VAT is recoverable (1150). Only a VAT-registered pharmacy names it.
 */
public record RecordReceiptCommand(
    UUID purchaseOrderUid,
    UUID supplierUid,
    String supplierInvoiceNumber,
    String note,
    List<ReceiptLineCommand> lines,
    /* The receipt's currency; null means the order's, or the base without an order. */
    String currency,
    /* Base units per 1 receipt unit; null means the order's locked rate, or today's rate. */
    java.math.BigDecimal exchangeRate,
    /* The invoice's VAT, on top of the lines; null or 0 for none. */
    java.math.BigDecimal vatAmount) {

  /** A receipt without VAT. */
  public RecordReceiptCommand(
      UUID purchaseOrderUid,
      UUID supplierUid,
      String supplierInvoiceNumber,
      String note,
      List<ReceiptLineCommand> lines,
      String currency,
      java.math.BigDecimal exchangeRate) {
    this(
        purchaseOrderUid,
        supplierUid,
        supplierInvoiceNumber,
        note,
        lines,
        currency,
        exchangeRate,
        null);
  }
}
