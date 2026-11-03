package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    BigDecimal baseTotal) {}
