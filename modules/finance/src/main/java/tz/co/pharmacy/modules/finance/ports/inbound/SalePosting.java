package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** A completed sale to post: net (excluding tax), tax, cost of goods, payments by method name. */
public record SalePosting(
    UUID saleUid,
    String number,
    UUID branchUid,
    LocalDate date,
    BigDecimal net,
    BigDecimal tax,
    BigDecimal cost,
    BigDecimal changeDue,
    Map<String, BigDecimal> paymentsByMethod) {}
