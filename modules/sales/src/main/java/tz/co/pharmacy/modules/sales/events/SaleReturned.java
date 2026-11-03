package tz.co.pharmacy.modules.sales.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * {@code sale.returned}: goods of a sale came back and were refunded. {@code refundTotal} is in the
 * base currency as booked; a foreign refund adds its currency, amount and the realised exchange
 * difference (base paid out − refundTotal).
 */
public record SaleReturned(
    EventMetadata metadata,
    UUID returnUid,
    String number,
    UUID saleUid,
    UUID branchUid,
    String refundMethod,
    BigDecimal refundTotal,
    List<SaleCompleted.Line> lines,
    String refundCurrency,
    BigDecimal refundForeignAmount,
    BigDecimal fxDifference)
    implements DomainEvent {}
