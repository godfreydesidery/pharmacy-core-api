package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.util.List;
import java.util.UUID;

/** Goods received at the current branch; {@code purchaseOrderUid} is optional. */
public record RecordReceiptCommand(
    UUID purchaseOrderUid,
    UUID supplierUid,
    String supplierInvoiceNumber,
    String note,
    List<ReceiptLineCommand> lines,
    /* The receipt's currency; null means the order's, or the base without an order. */
    String currency,
    /* Base units per 1 receipt unit; null means the order's locked rate, or today's rate. */
    java.math.BigDecimal exchangeRate) {}
