package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A goods receipt. {@code total} is what the supplier is owed: the lines plus {@code vatTotal}, the
 * invoice's input VAT (STD-2); {@code baseTotal} and {@code baseVatTotal} are both in the base
 * currency.
 */
public record GoodsReceiptView(
    UUID uid,
    String number,
    UUID branchUid,
    UUID purchaseOrderUid,
    String purchaseOrderNumber,
    UUID supplierUid,
    String supplierName,
    String supplierInvoiceNumber,
    String note,
    BigDecimal total,
    List<ReceiptLineView> lines,
    Instant createdAt,
    String createdBy,
    String currency,
    BigDecimal exchangeRate,
    BigDecimal baseTotal,
    BigDecimal vatTotal,
    BigDecimal baseVatTotal) {}
