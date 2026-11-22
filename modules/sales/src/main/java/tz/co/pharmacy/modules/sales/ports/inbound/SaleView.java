package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleView(
    UUID uid,
    String number,
    UUID branchUid,
    String branchName,
    UUID customerUid,
    String customerName,
    SaleStatus status,
    BigDecimal discountPercent,
    BigDecimal subtotal,
    BigDecimal discountTotal,
    BigDecimal taxTotal,
    BigDecimal total,
    BigDecimal paidTotal,
    BigDecimal changeDue,
    List<SaleLineView> lines,
    List<PaymentView> payments,
    PrescriptionDetails prescription,
    String fiscalReference,
    String cashierName,
    Instant createdAt,
    /* The pricing plan it was priced under (null: Normal Sale), as it was named then. */
    UUID priceListUid,
    String priceListName) {}
