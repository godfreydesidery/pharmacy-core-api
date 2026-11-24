package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleSummaryView(
    UUID uid,
    String number,
    String customerName,
    int itemCount,
    BigDecimal total,
    BigDecimal paidTotal,
    List<PaymentMethod> paymentMethods,
    SaleStatus status,
    String cashierName,
    Instant createdAt,
    /* Its fiscal receipt's status, null without one (STD-1). */
    FiscalStatus fiscalStatus) {}
