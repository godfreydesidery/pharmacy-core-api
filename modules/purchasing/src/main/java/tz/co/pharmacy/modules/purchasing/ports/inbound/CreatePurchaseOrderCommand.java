package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreatePurchaseOrderCommand(
    UUID supplierUid,
    LocalDate expectedDate,
    String note,
    List<OrderLineCommand> lines,
    /* ISO 4217 code of the order's prices; null means the base currency. */
    String currency,
    /* Base units per 1 order unit; null takes today's rate. Ignored for the base currency. */
    java.math.BigDecimal exchangeRate) {}
