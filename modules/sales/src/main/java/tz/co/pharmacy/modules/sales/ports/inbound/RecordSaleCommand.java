package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RecordSaleCommand(
    String number,
    String idempotencyKey,
    UUID customerUid,
    String customerName,
    BigDecimal discountPercent,
    List<SaleLineCommand> lines,
    List<PaymentCommand> payments,
    PrescriptionDetails prescription,
    UUID priceListUid,
    String priceListName,
    BigDecimal normalTotal) {}
