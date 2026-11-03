package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderView(
    UUID uid,
    String number,
    UUID branchUid,
    UUID supplierUid,
    String supplierName,
    PurchaseOrderStatus status,
    LocalDate expectedDate,
    String note,
    BigDecimal total,
    List<PurchaseOrderLineView> lines,
    Instant createdAt,
    String createdBy,
    String currency,
    BigDecimal exchangeRate,
    BigDecimal baseTotal) {}
